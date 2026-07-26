package com.rakesh.chat.server;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An append-only audit trail of every connection, one event per line.
 *
 * <pre>
 * # timestamp | event | nickname | remoteAddress | durationSeconds | detail
 * 2026-07-26T10:15:30.101Z | CONNECT       | -     | /127.0.0.1:51544 | 0.000  |
 * 2026-07-26T10:15:30.140Z | HANDSHAKE_OK  | alice | /127.0.0.1:51544 | 0.039  |
 * 2026-07-26T10:16:02.550Z | DISCONNECT    | alice | /127.0.0.1:51544 | 32.449 | QUIT
 * </pre>
 *
 * <h2>Why this does not write on the caller's thread</h2>
 *
 * Phase 3 established the rule that a handler thread never performs blocking I/O on
 * behalf of another connection. Disk is I/O too, and a synchronised {@code write} +
 * {@code flush} inside {@code cleanup()} would put every handler behind one lock held
 * across a filesystem call — a full disk, a stalled network mount or an antivirus scanner
 * holding the file would freeze the accept path. So this reuses the outbox pattern
 * exactly: a bounded queue, one dedicated daemon writer, {@code offer} at the call site.
 *
 * <h2>The overflow policy, and why it differs from the outbox</h2>
 *
 * The outbox drops the <i>client</i> when it overflows. That is wrong for an audit log —
 * losing a record is bad, but refusing service because the disk is slow is worse, and
 * blocking would reintroduce the exact stall this design exists to prevent. So overflow
 * <b>drops the record and counts it</b>, and the next line that does get written carries
 * {@code dropped=N}. The file therefore never lies by omission: a gap is always visible
 * as a number, never as silence. That is the property that matters for an audit trail —
 * completeness is unattainable under backpressure, but <i>detectability</i> is not.
 *
 * <h2>Durability</h2>
 *
 * The writer flushes after every batch, so records reach the OS page cache promptly, and
 * {@link #close()} drains and flushes. It does <b>not</b> {@code fsync}. A kernel panic
 * can therefore lose the last few records. Calling {@code FileChannel.force(true)} per
 * event would cost a disk round-trip each time; for a connection log that is the wrong
 * trade, and for anything where it is the right trade, this class is the wrong tool.
 */
public final class ConnectionLog implements Closeable {

    /** The five events the build guide specifies, and nothing else. */
    public enum Event {
        /** TCP connection accepted. Always the first line for a connection. */
        CONNECT,
        /** {@code HELLO} accepted, nickname reserved. */
        HANDSHAKE_OK,
        /**
         * The connection ended before it was ever joined — bad version, taken nickname,
         * rejected nickname, deadline expiry, or simply hanging up without a {@code HELLO}.
         */
        HANDSHAKE_FAIL,
        /** A joined client's connection ended: {@code QUIT}, EOF, or a TCP reset. */
        DISCONNECT,
        /** The <i>server</i> ended the connection: outbox overflow, rate abuse, shutdown. */
        KICKED
    }

    private static final String HEADER =
            "# timestamp | event | nickname | remoteAddress | durationSeconds | detail";

    /**
     * Large enough that a burst of a few thousand disconnects rides through, small enough
     * that a wedged disk costs a bounded amount of heap rather than an OOM.
     */
    private static final int QUEUE_CAPACITY = 10_000;

    private static final String POISON = new String("__POISON__");

    private final Path path;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closed = false;

    public ConnectionLog(Path path) throws IOException {
        this.path = path;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Write the header only when creating the file, so restarting the server appends
        // to one continuous record instead of peppering it with headers.
        if (Files.notExists(path) || Files.size(path) == 0) {
            Files.writeString(path, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
        this.writer = new Thread(this::drainLoop, "connection-log");
        // Daemon: an audit log must never be the reason the JVM refuses to exit. close()
        // is what guarantees the drain; the daemon flag only covers the case where
        // somebody forgot to call it.
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Queue one event. Never blocks, never throws.
     *
     * @param durationMillis milliseconds since this connection was accepted
     * @param detail         free text, or {@code null}; {@code |} and line breaks are
     *                       stripped so one field can never forge another
     */
    public void record(Event event, String nickname, String remoteAddress,
                       long durationMillis, String detail) {
        if (closed) {
            return;
        }
        long lost = dropped.get();
        String line = String.format(Locale.ROOT, "%s | %-14s | %-16s | %-21s | %8.3f | %s",
                Instant.now(),
                event,
                clean(nickname),
                clean(remoteAddress),
                durationMillis / 1000.0,
                lost > 0 ? clean(detail) + " (dropped=" + lost + ")" : clean(detail));

        if (!queue.offer(line)) {
            dropped.incrementAndGet();
        } else if (lost > 0) {
            // The count has now been reported in a line that is safely queued, so reset
            // it — but only by the amount reported, in case more were dropped meanwhile.
            dropped.addAndGet(-lost);
        }
    }

    /** Records currently lost to backpressure and not yet reported. Tests and diagnostics. */
    public long droppedRecords() {
        return dropped.get();
    }

    public Path path() {
        return path;
    }

    private void drainLoop() {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (true) {
                String line = queue.take();
                if (line == POISON) { // identity, not equality — see ClientHandler.POISON
                    break;
                }
                out.write(line);
                out.newLine();
                // Flush when the queue has gone quiet rather than on every line: a busy
                // server batches syscalls, an idle one loses nothing.
                if (queue.isEmpty()) {
                    out.flush();
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The log is diagnostics; failing to write it must not take the server down.
            ServerLog.error("LOG_FAILED", path.toString(), e.toString());
        }
    }

    /** Drains what is queued, flushes, and stops the writer. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!queue.offer(POISON)) {
            writer.interrupt(); // queue is wedged; the backlog is what we accept losing
        }
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer.interrupt(); // no-op if it already exited
    }

    /** Strips the field separator and line breaks so a field cannot forge structure. */
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
