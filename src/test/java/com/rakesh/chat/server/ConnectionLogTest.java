package com.rakesh.chat.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ConnectionLogTest {

    @TempDir
    Path tmp;

    private List<String> readLines(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    /** Every record line, header excluded. */
    private List<String> records(Path path) throws IOException {
        return readLines(path).stream().filter(l -> !l.startsWith("#")).toList();
    }

    @Test
    @DisplayName("creates the directory, writes a header, and records six fields")
    void writesAHeaderAndAWellFormedRecord() throws Exception {
        Path path = tmp.resolve("nested").resolve("dir").resolve("connections.log");

        try (ConnectionLog log = new ConnectionLog(path)) {
            log.record(ConnectionLog.Event.HANDSHAKE_OK, "alice", "/127.0.0.1:51544", 1234, null);
        }

        List<String> lines = readLines(path);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("# timestamp | event |"), lines.get(0));

        String[] fields = lines.get(1).split("\\|");
        assertEquals(6, fields.length, "expected six pipe-separated fields: " + lines.get(1));
        assertEquals("HANDSHAKE_OK", fields[1].trim());
        assertEquals("alice", fields[2].trim());
        assertEquals("/127.0.0.1:51544", fields[3].trim());
        assertEquals("1.234", fields[4].trim(), "duration is seconds to three decimals");
    }

    @Test
    @DisplayName("absent nickname and detail become '-', so the column count never varies")
    void nullFieldsBecomePlaceholders() throws Exception {
        Path path = tmp.resolve("c.log");
        try (ConnectionLog log = new ConnectionLog(path)) {
            log.record(ConnectionLog.Event.CONNECT, null, "/127.0.0.1:1", 0, null);
        }

        String[] fields = records(path).get(0).split("\\|");
        assertEquals(6, fields.length);
        assertEquals("-", fields[2].trim());
        assertEquals("-", fields[5].trim());
    }

    @Test
    @DisplayName("a detail field cannot forge a column or a record")
    void separatorsAreStrippedFromFields() throws Exception {
        Path path = tmp.resolve("c.log");
        try (ConnectionLog log = new ConnectionLog(path)) {
            log.record(ConnectionLog.Event.KICKED, "mallory", "/127.0.0.1:1", 0,
                    "reason | 9999.000 | forged\nDISCONNECT | admin");
        }

        List<String> records = records(path);
        assertEquals(1, records.size(), "an injected newline must not create a second record");
        assertEquals(6, records.get(0).split("\\|").length,
                "an injected pipe must not create extra columns");
        assertTrue(records.get(0).contains("forged DISCONNECT"),
                "the text should survive, only its structure removed: " + records.get(0));
    }

    @Test
    @DisplayName("restarting appends to one continuous record, with a single header")
    void reopeningAppendsWithoutAnotherHeader() throws Exception {
        Path path = tmp.resolve("c.log");

        try (ConnectionLog first = new ConnectionLog(path)) {
            first.record(ConnectionLog.Event.CONNECT, null, "/1", 0, "run-1");
        }
        try (ConnectionLog second = new ConnectionLog(path)) {
            second.record(ConnectionLog.Event.CONNECT, null, "/2", 0, "run-2");
        }

        List<String> lines = readLines(path);
        assertEquals(1, lines.stream().filter(l -> l.startsWith("#")).count());
        assertEquals(2, lines.stream().filter(l -> !l.startsWith("#")).count());
        assertTrue(lines.get(1).contains("run-1"));
        assertTrue(lines.get(2).contains("run-2"));
    }

    @Test
    @DisplayName("close() drains what is queued rather than discarding it")
    void closeIsADrainNotAKill() throws Exception {
        Path path = tmp.resolve("c.log");
        int count = 2000;

        try (ConnectionLog log = new ConnectionLog(path)) {
            for (int i = 0; i < count; i++) {
                log.record(ConnectionLog.Event.DISCONNECT, "u" + i, "/127.0.0.1:" + i, i, null);
            }
            // close() runs here, immediately, with most of those still in the queue.
        }

        assertEquals(count, records(path).size(),
                "close() must let the writer finish the backlog — the same poison-pill "
                        + "lesson as ClientHandler (Phase 4 bug B2)");
    }

    @Test
    @DisplayName("record() after close() is ignored, not an exception")
    void recordAfterCloseIsSilentlyDropped() throws Exception {
        Path path = tmp.resolve("c.log");
        ConnectionLog log = new ConnectionLog(path);
        log.close();

        assertDoesNotThrow(() ->
                log.record(ConnectionLog.Event.CONNECT, null, "/1", 0, null));
        assertEquals(0, records(path).size());
    }

    @Test
    @DisplayName("close() twice is harmless")
    void closeIsIdempotent() throws Exception {
        ConnectionLog log = new ConnectionLog(tmp.resolve("c.log"));
        log.close();
        assertDoesNotThrow(log::close);
    }

    @Test
    @DisplayName("concurrent writers produce whole lines, never interleaved fragments")
    void isThreadSafe() throws Exception {
        Path path = tmp.resolve("c.log");
        int threads = 12;
        int perThread = 300;

        try (ConnectionLog log = new ConnectionLog(path)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            log.record(ConnectionLog.Event.DISCONNECT,
                                    "user_" + id, "/127.0.0.1:" + i, i, "detail" + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            pool.shutdownNow();
        }

        List<String> records = records(path);
        assertEquals(threads * perThread, records.size());
        // The real assertion: no line is a splice of two records. One writer thread owns
        // the file, so this holds by construction — the test is here to keep it that way.
        for (String record : records) {
            assertEquals(6, record.split("\\|").length, "torn line: " + record);
        }
    }

}
