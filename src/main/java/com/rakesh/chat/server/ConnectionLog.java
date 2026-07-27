package com.rakesh.chat.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/**
 * Writes one line to {@code logs/connections.log} for every important thing that happens
 * to a connection, so you can see the whole history of a session afterwards.
 *
 * <pre>
 * # timestamp | event | nickname | remoteAddress | durationSeconds | detail
 * 2026-07-26T10:15:30.101Z | CONNECT       | -     | /127.0.0.1:51544 |  0.000 | -
 * 2026-07-26T10:15:30.140Z | HANDSHAKE_OK  | alice | /127.0.0.1:51544 |  0.039 | -
 * 2026-07-26T10:16:02.550Z | DISCONNECT    | alice | /127.0.0.1:51544 | 32.449 | QUIT
 * </pre>
 *
 * <p>{@code record()} is {@code synchronized} because many client threads call it at the
 * same time, and without the lock two half-written lines could end up mixed together.
 */
public class ConnectionLog implements Closeable {

    /** The five things worth recording about a connection. */
    public enum Event {
        /** A client connected. Always the first line for a connection. */
        CONNECT,
        /** The client sent a valid HELLO and got its nickname. */
        HANDSHAKE_OK,
        /** The connection ended before the client ever joined the room. */
        HANDSHAKE_FAIL,
        /** A joined client left: QUIT, closed window, or lost network. */
        DISCONNECT,
        /** The server ended the connection: spamming, slow reading, or shutdown. */
        KICKED
    }

    private static final String HEADER =
            "# timestamp | event | nickname | remoteAddress | durationSeconds | detail";

    private final Path path;
    private final PrintWriter out;
    private boolean closed = false;

    public ConnectionLog(Path path) throws IOException {
        this.path = path;

        // Create the logs/ folder if it is not there yet.
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Only write the header the first time, so restarting the server keeps one
        // continuous file instead of sprinkling headers through it.
        boolean isNewFile = Files.notExists(path) || Files.size(path) == 0;

        // true = flush after every println, so the file is readable while the server runs.
        this.out = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                true);

        if (isNewFile) {
            out.println(HEADER);
        }
    }

    /**
     * Writes one event. Never throws, so a logging problem can never kill a connection.
     *
     * @param durationMillis how long this connection has been alive
     * @param detail         free text, or null
     */
    public synchronized void record(Event event, String nickname, String remoteAddress,
                                    long durationMillis, String detail) {
        if (closed) {
            return;
        }
        out.println(String.format(Locale.ROOT, "%s | %-14s | %-16s | %-21s | %8.3f | %s",
                Instant.now(),
                event,
                clean(nickname),
                clean(remoteAddress),
                durationMillis / 1000.0,
                clean(detail)));
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        out.close();
    }

    /**
     * Replaces '|' and line breaks with a space, and nulls with "-".
     *
     * <p>Without this, a user could pick a nickname containing '|' and fake extra columns,
     * or a newline and fake a whole extra log record.
     */
    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append((c == '|' || c == '\n' || c == '\r') ? ' ' : c);
        }
        return sb.toString();
    }
}
