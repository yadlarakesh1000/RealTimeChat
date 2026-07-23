package com.rakesh.chat.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;


  public class EchoServer {

    private final int port;

    public EchoServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            // Optional for easier restarts during development
            serverSocket.setReuseAddress(true);

            System.out.printf("[%s] Listening on port %d%n",
                    LocalTime.now(), port);

            try (Socket clientSocket = serverSocket.accept()) {

                System.out.printf("[%s] Client connected: %s%n",
                        LocalTime.now(),
                        clientSocket.getRemoteSocketAddress());

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        clientSocket.getInputStream(),
                                        StandardCharsets.UTF_8));

                PrintWriter writer =
                        new PrintWriter(
                                new BufferedWriter(
                                        new OutputStreamWriter(
                                                clientSocket.getOutputStream(),
                                                StandardCharsets.UTF_8)),
                                true); // autoFlush on println()

                try {

                    String line;

                    while ((line = reader.readLine()) != null) {

                        System.out.printf("[%s] %s%n",
                                LocalTime.now(),
                                line);

                        writer.println("ECHO: " + line);
                    }

                    System.out.printf("[%s] Client disconnected.%n",
                            LocalTime.now());

                } catch (SocketException e) {

                    System.out.printf("[%s] Connection lost: %s%n",
                            LocalTime.now(),
                            e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) {

        int port = 5000;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port. Using default port 5000.");
            }
        }

        EchoServer server = new EchoServer(port);

        try {
            server.start();
        } catch (BindException e) {
            System.out.println("Port " + port + " is already in use.");
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}

