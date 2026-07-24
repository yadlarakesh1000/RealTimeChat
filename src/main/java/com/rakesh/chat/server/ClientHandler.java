package com.rakesh.chat.server;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler implements Runnable {

    private final Socket socket;

    private final ChatServer server;

    private final BufferedReader in;

    private final PrintWriter out;

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    public ClientHandler(Socket socket,
                         ChatServer server) throws IOException {

        this.socket = socket;
        this.server = server;

        // Establish streams here (not in run()) so that `in` and `out`
        // are final and safely published before any other thread can
        // call send(). UTF-8 is explicit on BOTH directions.
        this.in = new BufferedReader(
                new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.UTF_8));

        this.out = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8)),
                true); // autoFlush on println()

    }

    @Override
    public void run() {

        try {

            send("Welcome!");

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

        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        try {
            socket.close();
        } catch (IOException ignored) {
        }

        // Phase 3:
        // server.removeClient(this);

    }

}
