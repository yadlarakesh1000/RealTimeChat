package com.rakesh.chat.client;

import com.rakesh.chat.common.BoundedLineReader;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;
import com.rakesh.chat.common.crypto.MessageCrypto;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The client half of the chat: opens the socket, says HELLO, and reads lines forever.
 *
 * <p><b>There is not a single JavaFX import in this file, and that is the point.</b> This
 * class knows about sockets and {@link Message} objects and nothing else. Because of that
 * it can be unit-tested with plain JUnit (see {@code ChatClientTest}), and the GUI on top
 * of it could be swapped for a console app without touching a line here.
 *
 * <p><b>Threads.</b> {@link #connect} starts one background thread that does nothing but
 * read lines. Every message it reads is handed to the listener <i>on that reader thread</i>
 * — so whoever sets the listener is responsible for getting back to their own thread. For
 * the JavaFX window that means {@code Platform.runLater}.
 */
public class ChatClient {

    /** Give up if the server does not answer the TCP connect within this long. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;
    private String nickname;

    /** Phase 8. Set by {@link #connect}; {@code OFF} until then and when no passphrase is given. */
    private MessageCrypto crypto = MessageCrypto.OFF;

    /** Read by the UI thread, written by the reader thread, hence volatile. */
    private volatile boolean connected = false;

    /** Called for every message the server sends. Runs on the reader thread. */
    private Consumer<Message> onMessage = message -> { };

    /** Called once when the connection ends by itself. Runs on the reader thread. */
    private Consumer<String> onClosed = reason -> { };

    public void setListener(Consumer<Message> onMessage) {
        this.onMessage = (onMessage == null) ? message -> { } : onMessage;
    }

    public void setOnClosed(Consumer<String> onClosed) {
        this.onClosed = (onClosed == null) ? reason -> { } : onClosed;
    }

    /** Connects with no encryption, the way Phases 1-7 did. */
    public void connect(String host, int port, String nickname) throws IOException {
        connect(host, port, nickname, null);
    }

    /**
     * Connects, starts the reader thread, and sends the HELLO handshake.
     *
     * <p>This blocks until the TCP connection is made, so never call it from the JavaFX
     * thread — the window would freeze while it waited. Phase 8 gives it a second reason:
     * turning the passphrase into a key deliberately takes about a tenth of a second.
     *
     * @param passphrase Phase 8. null or blank means plain text. Must match the server's
     *                   passphrase exactly, or every message will come back
     *                   {@code ERROR BAD_PAYLOAD}.
     */
    public void connect(String host, int port, String nickname, String passphrase)
            throws IOException {
        if (connected) {
            throw new IllegalStateException("already connected");
        }

        // Done before the socket is opened: if the passphrase is unusable we would rather
        // fail without having bothered the server.
        this.crypto = MessageCrypto.forPassphrase(passphrase);

        // new Socket() then connect(), rather than new Socket(host, port), because only
        // this form lets us put a timeout on the connect attempt.
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);

        // Same layering as the server, and the same UTF-8 that PROTOCOL.md section 1 requires.
        out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true); // autoflush on println

        BoundedLineReader in = new BoundedLineReader(socket.getInputStream());

        this.nickname = nickname;
        this.connected = true;

        // Daemon, so closing the window is enough to end the program. A normal thread
        // parked in read() would keep the JVM alive after the last window closed.
        readerThread = new Thread(() -> readLoop(in), "chat-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        send(Message.hello(nickname));
    }

    /**
     * Writes one message. Synchronized because the send button and the Enter key can both
     * land here, and two threads sharing a PrintWriter is how you get interleaved lines.
     *
     * <p>Phase 8: the one place outbound bodies get encrypted, mirroring the one decrypt
     * call in {@link #readLoop}.
     */
    public synchronized void send(Message message) {
        if (!connected || out == null) {
            return;
        }
        out.println(crypto.encrypt(message).serialize());
    }

    /** Phase 8. True when a passphrase was supplied to {@link #connect}. */
    public boolean isEncrypted() {
        return crypto.isOn();
    }

    /** Says QUIT politely, then closes. Safe to call twice. */
    public void disconnect() {
        if (!connected) {
            return;
        }
        send(Message.quit());
        connected = false;
        closeSocket();
    }

    public boolean isConnected() {
        return connected;
    }

    public String getNickname() {
        return nickname;
    }

    /** The whole life of the reader thread. */
    private void readLoop(BoundedLineReader in) {
        String reason = "the server closed the connection";
        try {
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    break; // clean end of stream
                }
                try {
                    // Phase 8: decrypt after parsing, exactly as the server does. A body we
                    // cannot decrypt lands here as a ProtocolException and is skipped like
                    // any other unreadable line.
                    Message incoming = crypto.decrypt(Message.parse(line));

                    // Phase 9. Answer the heartbeat here and stop. It is housekeeping
                    // between the two sockets, not something a user typed, so the window
                    // never hears about it and no "ping" ever appears in the conversation.
                    // Answering from this thread is safe because send() is synchronized.
                    if (incoming.type() == MessageType.PING) {
                        send(Message.pong());
                        continue;
                    }

                    onMessage.accept(incoming);
                } catch (ProtocolException e) {
                    // The server said something this version does not understand. Skip the
                    // line rather than dropping the connection - PROTOCOL.md section 2.6.
                    System.err.println("ignoring unreadable line: " + e.getMessage());
                }
            }
        } catch (ProtocolException e) {
            reason = "the server sent an over-long line";
        } catch (IOException e) {
            reason = "connection lost: " + e.getMessage();
        } finally {
            boolean wasConnected = connected;
            connected = false;
            closeSocket();
            // If the user pressed Disconnect, connected was already false and they know
            // perfectly well the connection ended. Only report a surprise.
            if (wasConnected) {
                onClosed.accept(reason);
            }
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
    }
}
