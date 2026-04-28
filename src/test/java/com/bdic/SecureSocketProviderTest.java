package com.bdic;

import com.bdic.net.SecureSocketProvider;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SecureSocketProviderTest extends TestCase {

    public void testTlsClientAndServerCanExchangeData() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket serverSocket = SecureSocketProvider.createServerSocket(0)) {
            Future<String> serverResult = executor.submit(() -> {
                try (Socket accepted = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(accepted.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    String line = reader.readLine();
                    writer.println("echo:" + line);
                    return line;
                }
            });

            try (Socket clientSocket = SecureSocketProvider.createClientSocket("127.0.0.1", serverSocket.getLocalPort());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println("ping");
                assertEquals("echo:ping", reader.readLine());
            }

            assertEquals("ping", serverResult.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
