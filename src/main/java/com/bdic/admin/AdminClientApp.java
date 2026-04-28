package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端桌面客户端入口。
 *
 * <p>职责：窗口入口、Tab 装配、全局状态协调与连接生命周期管理。</p>
 */
public class AdminClientApp extends JFrame {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 12345;
    private static final int EMBEDDED_SERVER_RETRIES = 20;
    private static final long EMBEDDED_SERVER_RETRY_DELAY_MS = 300L;
    private static final long MAX_AUTOMATIC_TEXT_KEYWORD_BYTES = 10L * 1024 * 1024;
    private static final int WINDOW_BASE_WIDTH = 760;
    private static final int WINDOW_BASE_HEIGHT = 560;

    private final ClientKeyManager keyManager = new ClientKeyManager();

    private String currentUsername;
    private ClientKeyManager.KeyBundle keyBundle;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private DocumentServiceClient serviceClient;
    private DocumentOperationService operationService;
    private UiBusyStateManager busyStateManager;

    private UploadPanelController uploadController;
    private SearchPanelController searchController;
    private DocumentsPanelController documentsController;

    private JButton logoutButton;

    public AdminClientApp() {
        try {
            connectToServer();
            serviceClient = new DocumentServiceClient(out, in);
            if (!showAuthenticationDialog()) {
                closeConnection();
                dispose();
                return;
            }
            operationService = new DocumentOperationService(MAX_AUTOMATIC_TEXT_KEYWORD_BYTES);
            createUI();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to initialize client: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void connectToServer() throws Exception {
        try {
            openConnection();
            return;
        } catch (Exception firstFailure) {
            if (!isConnectionRefused(firstFailure)) {
                throw firstFailure;
            }
            System.out.println("No TLS server found on " + HOST + ":" + PORT + ". Starting embedded server...");
        }

        Server.startEmbedded();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= EMBEDDED_SERVER_RETRIES; attempt++) {
            try {
                Thread.sleep(EMBEDDED_SERVER_RETRY_DELAY_MS);
                openConnection();
                return;
            } catch (Exception retryFailure) {
                lastFailure = retryFailure;
            }
        }

        throw new IOException("Embedded server did not become ready on " + HOST + ":" + PORT, lastFailure);
    }

    private void openConnection() throws Exception {
        socket = SecureSocketProvider.createClientSocket(HOST, PORT);
        out = new ObjectOutputStream(socket.getOutputStream());
        in = new ObjectInputStream(socket.getInputStream());
        System.out.println("Connected to TLS server at " + HOST + ":" + PORT);
    }

    private boolean isConnectionRefused(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean showAuthenticationDialog() throws Exception {
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        Object[] message = {
                "Username:", usernameField,
                "Password:", passwordField
        };

        while (true) {
            Object[] options = {"Login", "Register", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    message,
                    "Authentication",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }

            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Username and password are required.", "Warning", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            ServerResponse response = (choice == 1)
                    ? serviceClient.register(username, password)
                    : serviceClient.login(username, password);
            showResponse(response, "Authentication");
            if (!response.isSuccess()) {
                continue;
            }

            currentUsername = username;
            if (response.getData() instanceof SessionInfo sessionInfo) {
                currentUsername = sessionInfo.getUsername();
            }

            keyBundle = keyManager.loadOrCreate(currentUsername);
            return true;
        }
    }

    private void createUI() {
        setTitle("Searchable Encryption System - Client");
        setSize(WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                logoutAndExit(false);
            }
        });

        JLabel userInfoLabel = new JLabel("Current User: " + currentUsername);
        userInfoLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logoutAndExit(true));
        UiComponentFactory.styleDangerButton(logoutButton);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(userInfoLabel, BorderLayout.CENTER);
        headerPanel.add(logoutButton, BorderLayout.EAST);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        add(headerPanel, BorderLayout.NORTH);

        uploadController = new UploadPanelController(this, serviceClient, operationService, keyBundle, this::refreshDocuments);
        searchController = new SearchPanelController(this, serviceClient, operationService, keyBundle);
        documentsController = new DocumentsPanelController(this, serviceClient, operationService, keyBundle);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Upload", uploadController.createPanel());
        tabbedPane.addTab("Search", searchController.createPanel());
        tabbedPane.addTab("Documents", documentsController.createPanel());
        add(tabbedPane, BorderLayout.CENTER);

        busyStateManager = new UiBusyStateManager(
                this,
                uploadController.getStatusLabel(),
                uploadController.getProgressBar(),
                searchController.getStatusLabel(),
                searchController.getProgressBar(),
                documentsController.getStatusLabel(),
                documentsController.getProgressBar()
        );

        uploadController.setBusyStateManager(busyStateManager);
        searchController.setBusyStateManager(busyStateManager);
        documentsController.setBusyStateManager(busyStateManager);

        List<JComponent> busySensitive = new ArrayList<>();
        busySensitive.addAll(uploadController.getBusySensitiveComponents());
        busySensitive.addAll(searchController.getBusySensitiveComponents());
        busySensitive.addAll(documentsController.getBusySensitiveComponents());
        busySensitive.add(logoutButton);
        busyStateManager.registerBusySensitiveComponents(busySensitive);

        UiScaleManager.install(this, WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        refreshDocuments();
    }

    private void refreshDocuments() {
        if (documentsController != null) {
            documentsController.refreshDocuments();
        }
    }

    private void showResponse(ServerResponse response, String title) {
        JOptionPane.showMessageDialog(
                this,
                response.getMessage(),
                title,
                response.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
        );
    }

    private void logoutAndExit(boolean showDialog) {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            JOptionPane.showMessageDialog(this, "An operation is still in progress. Please wait for it to finish.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            if (serviceClient != null) {
                ServerResponse response = serviceClient.logout();
                if (showDialog) {
                    showResponse(response, "Logout");
                }
            }
        } catch (Exception e) {
            if (showDialog) {
                JOptionPane.showMessageDialog(this, "Logout failed: " + e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
            }
        } finally {
            closeConnection();
            dispose();
            System.exit(0);
        }
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            AdminClientApp clientApp = new AdminClientApp();
            if (clientApp.currentUsername != null) {
                clientApp.setVisible(true);
            }
        });
    }
}
