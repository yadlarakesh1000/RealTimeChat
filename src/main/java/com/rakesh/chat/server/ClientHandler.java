package com.rakesh.chat.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ChatServer server;
    private final BufferedReader in;
    private final PrintWriter out;
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    private final BlockingQueue<String> outbox;
    private final Thread writerThread;
    private volatile String nickname;
    private final ClientRegistry registry;

    public ClientHandler(Socket socket, ChatServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.registry = server.getRegistry();

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

        // Phase 3 additions:
        // Bound the queue to 256 messages.
        this.outbox = new LinkedBlockingQueue<>(256);
        // Start writerThread in run(), not the constructor, to avoid unsafe-publication bugs.
        this.writerThread = new Thread(this::writerLoop, "writer-unnamed");
    }

    @Override
    public void run() {
        try {
            send("Welcome! Please enter your nickname:");
            String nickLine = in.readLine();
            if (nickLine == null) {
                return;
            }
            nickLine = nickLine.trim();
            if (nickLine.isEmpty()) {
                send("ERROR: Nickname cannot be empty.");
                return;
            }

            if (!registry.register(nickLine, this)) {
                send("ERROR: Nickname already taken.");
                return;
            }

            this.nickname = nickLine;

            // Rename threads for debuggability and logging
            Thread.currentThread().setName("reader-" + nickname);
            writerThread.setName("writer-" + nickname);

            // Safe publication: start writer loop now that object is fully built and name is registered
            writerThread.start();

            send("Welcome to the chat, " + nickname + "!");
            registry.broadcast("[JOINED] " + nickname, this);

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[" + nickname + "] " + line);
                registry.broadcast("[" + nickname + "] " + line, this);
            }

            System.out.println(nickname + " disconnected.");
            registry.broadcast("[LEFT] " + nickname, this);

        } catch (Throwable t) {
            System.err.println("Error in ClientHandler for " 
                    + (nickname != null ? nickname : socket.getRemoteSocketAddress()) 
                    + ": " + t.getMessage());
            t.printStackTrace();
        } finally {
            cleanup();
        }
    }

    /**
     * Non-blocking send. Offers message to the bounded queue.
     * Returns true if queued, false if full (indicating a slow consumer).
     */
    public boolean send(String message) {
        return outbox.offer(message);
    }

    /**
     * Dedicated writer thread loop.
     * Reads from the outbox queue and writes to the socket PrintWriter.
     */
    private void writerLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String message = outbox.take();
                out.println(message);
                if (out.checkError()) {
                    System.err.println("Writer thread detected socket error for " + nickname);
                    break;
                }
            }
        } catch (InterruptedException e) {
            // Exit loop when interrupted
        } finally {
            cleanup();
        }
    }

    /**
     * Idempotent and thread-safe cleanup method.
     * unregister → signal writer → join(timeout) → close socket.
     */
    public void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        // 1. unregister
        if (nickname != null) {
            registry.unregister(nickname, this);
        }

        // 2 & 3. signal writer (interrupt) and join writer thread (with 1s timeout)
        // Avoid self-joining if called from within the writerThread itself.
        if (Thread.currentThread() != writerThread) {
            writerThread.interrupt();
            try {
                writerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            writerThread.interrupt();
        }

        // 4. close socket
        try {
            socket.close();
        } catch (IOException ignored) {
        }

        // 5. decrement active connections count
        server.decrementActiveConnections();
    }

    public String getNickname() {
        return nickname;
    }
}
