package com.bdic.admin;

import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.db.UserRepository;
import com.bdic.model.DocumentRequest;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端入口。
 *
 * <p>负责启动 TLS 监听、维护登录会话，并处理客户端发来的注册、登录、上传、搜索、
 * 下载、删除等请求。AdminClientApp 可以通过 startEmbedded() 在同一 JVM 内启动该服务端。</p>
 */
public class Server {

    private static final int PORT = 12345;
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(
            Long.getLong("se.session.timeout.minutes", 30)
    );
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean EMBEDDED_SERVER_STARTED = new AtomicBoolean(false);

    private final EncryptedDataRepository repository;
    private final UserRepository userRepository;

    public Server() {
        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initialize();
        this.repository = new EncryptedDataRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
    }

    /**
     * 启动阻塞式服务端监听循环。该方法适合单独运行服务端或在后台线程中运行。
     */
    public void start() {
        try (ServerSocket serverSocket = SecureSocketProvider.createServerSocket(PORT)) {
            System.out.println("TLS server is listening on port " + PORT);

            while (true) {
                cleanupExpiredSessions();
                Socket clientSocket = serverSocket.accept();
                System.out.println("New TLS client connected: " + clientSocket.getInetAddress());
                new ClientHandler(clientSocket).start();
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 启动嵌入式服务端。若已经启动过，则直接返回，避免重复占用端口。
     */
    public static void startEmbedded() {
        if (!EMBEDDED_SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread serverThread = new Thread(() -> {
            try {
                new Server().start();
            } catch (Exception e) {
                EMBEDDED_SERVER_STARTED.set(false);
                throw e;
            }
        }, "searchable-encryption-embedded-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * 清理已过期 session，避免长时间运行时会话表无限增长。
     */
    private static void cleanupExpiredSessions() {
        Instant now = Instant.now();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    /**
     * 单个客户端连接的处理线程。
     */
    private class ClientHandler extends Thread {
        private final Socket socket;
        private String currentSessionId;
        private String currentUsername;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        /**
         * 持续读取客户端消息，并按消息类型分发到具体业务逻辑。
         */
        @Override
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                while (true) {
                    NetworkMessage message = (NetworkMessage) in.readObject();
                    if (message == null) {
                        break;
                    }

                    try {
                        switch (message.getType()) {
                            case REGISTER:
                                handleRegister(message, out);
                                break;

                            case LOGIN:
                                handleLogin(message, out);
                                break;

                            case LOGOUT:
                                closeSession();
                                writeResponse(out, true, "Logged out.", null);
                                break;

                            case UPLOAD:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                EncryptedData data = (EncryptedData) message.getPayload();
                                repository.save(currentUsername, data);
                                System.out.println("Stored document " + data.getDocId() + " for " + currentUsername);
                                writeResponse(out, true, "Upload succeeded.", null);
                                break;

                            case SEARCH:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                byte[] trapdoor = (byte[]) message.getPayload();
                                List<EncryptedData> matchedData = repository.searchByTrapdoor(currentUsername, trapdoor);
                                writeResponse(out, true, "Search completed.", matchedData);
                                break;

                            case LIST_DOCUMENTS:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                List<DocumentSummary> documentSummaries = repository.listDocuments(currentUsername);
                                writeResponse(out, true, "Document list loaded.", documentSummaries);
                                break;

                            case DOWNLOAD_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest downloadRequest = (DocumentRequest) message.getPayload();
                                EncryptedData document = repository.findByOwnerAndDocId(currentUsername, downloadRequest.getDocId());
                                if (document == null) {
                                    writeResponse(out, false, "Document not found.", null);
                                } else {
                                    writeResponse(out, true, "Download ready.", document);
                                }
                                break;

                            case DELETE_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest deleteRequest = (DocumentRequest) message.getPayload();
                                boolean deleted = repository.deleteByOwnerAndDocId(currentUsername, deleteRequest.getDocId());
                                writeResponse(out, deleted, deleted ? "Delete succeeded." : "Document not found.", null);
                                break;

                            default:
                                writeResponse(out, false, "Unsupported message type: " + message.getType(), null);
                        }
                    } catch (Exception e) {
                        String clientMessage = buildClientSafeErrorMessage(message.getType(), e);
                        System.err.println("Client request failed: " + clientMessage);
                        e.printStackTrace();
                        writeResponse(out, false, clientMessage, null);
                    }
                }
            } catch (java.io.EOFException e) {
                System.out.println("Client disconnected.");
            } catch (Exception e) {
                System.err.println("Client handler exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                closeSession();
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * 注册成功后立即创建登录会话。
         */
        private void handleRegister(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            boolean created = userRepository.register(loginRequest.getUsername(), loginRequest.getPassword());
            if (!created) {
                writeResponse(out, false, "Username already exists.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Registration succeeded.");
        }

        /**
         * 校验用户名密码，成功后创建登录会话。
         */
        private void handleLogin(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            boolean authenticated = userRepository.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
            if (!authenticated) {
                writeResponse(out, false, "Invalid username or password.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Login succeeded.");
        }

        /**
         * 创建新的服务端 session，并把会话信息返回给客户端。
         */
        private void openSession(String username, ObjectOutputStream out, String message) throws IOException {
            closeSession();
            Session session = Session.create(username);
            SESSIONS.put(session.sessionId, session);
            currentSessionId = session.sessionId;
            currentUsername = username;
            writeResponse(out, true, message, session.toInfo());
        }

        /**
         * 校验当前连接是否已登录、session 是否仍有效；有效时顺便刷新过期时间。
         */
        private boolean ensureAuthenticated(ObjectOutputStream out) throws IOException {
            if (currentSessionId == null || currentUsername == null || currentUsername.isBlank()) {
                writeResponse(out, false, "Please log in first.", null);
                return false;
            }

            Session session = SESSIONS.get(currentSessionId);
            if (session == null || !currentUsername.equals(session.username)) {
                currentSessionId = null;
                currentUsername = null;
                writeResponse(out, false, "Session is no longer valid. Please log in again.", null);
                return false;
            }

            if (session.isExpired()) {
                closeSession();
                writeResponse(out, false, "Session expired. Please log in again.", null);
                return false;
            }

            session.refresh();
            return true;
        }

        /**
         * 主动移除当前连接绑定的 session。
         */
        private void closeSession() {
            if (currentSessionId != null) {
                SESSIONS.remove(currentSessionId);
            }
            currentSessionId = null;
            currentUsername = null;
        }

        /**
         * 按统一协议向客户端写回响应。
         */
        private void writeResponse(ObjectOutputStream out, boolean success, String message, Object data) throws IOException {
            out.writeObject(new NetworkMessage(
                    NetworkMessage.MessageType.RESPONSE,
                    new ServerResponse(success, message, data)
            ));
            out.flush();
        }
    }

    private static String buildClientSafeErrorMessage(NetworkMessage.MessageType type, Exception exception) {
        Throwable rootCause = findRootCause(exception);
        String detail = rootCause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = rootCause.getClass().getSimpleName();
        }
        if ("PacketTooBigException".equals(rootCause.getClass().getSimpleName())) {
            return inferActionLabel(type) + " failed: file is too large for the current MySQL max_allowed_packet limit. " + detail;
        }
        return inferActionLabel(type) + " failed: " + detail;
    }

    private static String inferActionLabel(NetworkMessage.MessageType type) {
        if (type == null) {
            return "Request";
        }
        return switch (type) {
            case REGISTER -> "Registration";
            case LOGIN -> "Login";
            case LOGOUT -> "Logout";
            case UPLOAD -> "Upload";
            case SEARCH -> "Search";
            case LIST_DOCUMENTS -> "Document list";
            case DOWNLOAD_DOCUMENT -> "Download";
            case DELETE_DOCUMENT -> "Delete";
            case RESPONSE -> "Request";
        };
    }

    private static Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 服务端内存会话对象。
     */
    private static class Session {
        private final String sessionId;
        private final String username;
        private volatile Instant expiresAt;

        private Session(String sessionId, String username, Instant expiresAt) {
            this.sessionId = sessionId;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        /**
         * 创建带随机 sessionId 的新会话。
         */
        private static Session create(String username) {
            return new Session(UUID.randomUUID().toString(), username, Instant.now().plus(SESSION_TIMEOUT));
        }

        private boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }

        private void refresh() {
            expiresAt = Instant.now().plus(SESSION_TIMEOUT);
        }

        /**
         * 将内部会话转换为可序列化给客户端的 DTO。
         */
        private SessionInfo toInfo() {
            LocalDateTime localExpiresAt = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
            return new SessionInfo(sessionId, username, localExpiresAt);
        }
    }

}
