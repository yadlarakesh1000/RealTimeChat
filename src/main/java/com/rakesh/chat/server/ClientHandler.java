package com.rakesh.chat.server;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {

    private final Socket socket;

    private final ChatServer server;

    private BufferedReader in;

    private PrintWriter out;

    private volatile boolean cleanedUp = false;

    public ClientHandler(Socket socket,
                         ChatServer server) {

        this.socket = socket;
        this.server = server;

    }

    @Override
    public void run() {

        try {

            in = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));

            out = new PrintWriter(
                    socket.getOutputStream(),
                    true);

            out.println("Welcome!");

            String line;

            while ((line = in.readLine()) != null) {

                System.out.println(
                        "[" +
                                socket.getRemoteSocketAddress()
                                + "] "
                                + line);

                send("Echo: " + line);

            }

            System.out.println(
                    socket.getRemoteSocketAddress()
                            + " disconnected.");

        } catch (IOException e) {

            System.out.println(
                    "Connection lost: "
                            + socket.getRemoteSocketAddress());

        } finally {

            cleanup();

        }

    }

    /**
     * Called by other threads.
     * Synchronize to prevent interleaved writes.
     */
    public void send(String message) {

        synchronized (out) {

            out.println(message);

        }

    }

    private void cleanup() {

        if (cleanedUp) {
            return;
        }

        cleanedUp = true;

        try {
            socket.close();
        } catch (IOException ignored) {
        }

        // Phase 3:
        // server.removeClient(this);

    }

}
