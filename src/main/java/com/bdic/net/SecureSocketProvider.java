package com.bdic.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * TLS Socket 工厂。
 *
 * <p>客户端和服务端共用项目内置的开发证书，保证本地通信走加密通道。该证书仅适合教学和本地演示，
 * 如果部署到真实环境，应替换为受信任 CA 签发的证书，并妥善管理密钥库密码。</p>
 */
public final class SecureSocketProvider {

    private static final String KEY_STORE_RESOURCE = "/tls/searchable-encryption-dev.p12";
    private static final char[] KEY_STORE_PASSWORD = "changeit".toCharArray();
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private SecureSocketProvider() {
    }

    /**
     * 创建服务端 TLS 监听 Socket。
     */
    public static ServerSocket createServerSocket(int port) throws IOException, GeneralSecurityException {
        SSLContext context = createServerContext();
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        configureProtocols(serverSocket);
        serverSocket.setNeedClientAuth(false);
        return serverSocket;
    }

    /**
     * 创建客户端 TLS Socket，并主动完成握手。
     */
    public static Socket createClientSocket(String host, int port) throws IOException, GeneralSecurityException {
        SSLContext context = createClientContext();
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        configureProtocols(socket);

        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    /**
     * 服务端上下文需要加载私钥，用于向客户端证明服务端身份。
     */
    private static SSLContext createServerContext() throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEY_STORE_PASSWORD);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    /**
     * 客户端上下文只需要信任内置证书，用于校验服务端证书。
     */
    private static SSLContext createClientContext() throws IOException, GeneralSecurityException {
        KeyStore trustStore = loadKeyStore();

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * 从 classpath 读取 PKCS12 密钥库。
     */
    private static KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = SecureSocketProvider.class.getResourceAsStream(KEY_STORE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("TLS key store resource not found: " + KEY_STORE_RESOURCE);
            }
            keyStore.load(inputStream, KEY_STORE_PASSWORD);
        }
        return keyStore;
    }

    /**
     * 仅启用当前 JDK 支持的 TLSv1.3/TLSv1.2。
     */
    private static void configureProtocols(SSLSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    private static void configureProtocols(SSLServerSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    private static String[] selectSupportedProtocols(String[] supportedProtocols) {
        return Arrays.stream(PREFERRED_PROTOCOLS)
                .filter(protocol -> Arrays.asList(supportedProtocols).contains(protocol))
                .toArray(String[]::new);
    }
}
