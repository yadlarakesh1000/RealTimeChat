package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * All the settings the server uses, kept in one place instead of scattered as magic
 * numbers around the code.
 *
 * <p>Usage: make one, change the fields you care about, hand it to {@link ChatServer}.
 *
 * <pre>
 *   ServerConfig config = ServerConfig.defaults();
 *   config.port = 6000;
 *   ChatServer server = new ChatServer(config);
 * </pre>
 *
 * <p>The main reason this class exists is testing. A 10 second handshake deadline is
 * sensible for real users but painful in a test, so the test just sets it to 300 ms.
 *
 * <p><b>Phase 9</b> adds {@link #load(Path)}, so the same settings can come from
 * {@code server.properties} without recompiling. Changing a limit on a running deployment
 * should not need a compiler.
 */
public class ServerConfig {

    /** Port to listen on. 0 means "let the OS pick a free port" — used by the tests. */
    public int port = 5000;

    /** How many clients may be connected at the same time. */
    public int maxClients = 100;

    /** Longest line we will accept, in bytes (PROTOCOL.md section 1). */
    public int maxLineBytes = BoundedLineReader.DEFAULT_MAX_BYTES;

    /** How many messages we can hold for one client before we give up on them. */
    public int outboxCapacity = 256;

    /** A new client must send HELLO within this many milliseconds. */
    public int handshakeTimeoutMillis = 10_000;

    /**
     * After HELLO, drop a client that sends nothing at all for this long (15 minutes).
     *
     * <p>Phase 9 note: a {@code PONG} does <b>not</b> count as traffic here, otherwise a
     * client could sit in the room forever by answering heartbeats. This deadline is about
     * a user who has wandered off; {@link #pingIntervalMillis} is about a socket that is
     * already dead. Two different questions, two different settings.
     */
    public int idleTimeoutMillis = 900_000;

    /**
     * Phase 9. After this much silence the server sends {@code PING} and expects
     * {@code PONG} back.
     *
     * <p>This is also the socket read timeout after the handshake, so it is how often the
     * reader thread wakes up to check its deadlines.
     */
    public int pingIntervalMillis = 30_000;

    /**
     * Phase 9. How many unanswered {@code PING}s end the connection.
     *
     * <p>Two, not one, so that a single lost packet or a client that is briefly busy does
     * not get somebody kicked out. Dead sockets are therefore noticed after roughly
     * {@code pingIntervalMillis × (missedPongsBeforeKick + 1)} — 90 seconds by default.
     */
    public int missedPongsBeforeKick = 2;

    /**
     * Phase 9. How long shutdown waits for the goodbye line to reach everybody.
     *
     * <p>Shutdown closes sockets, which throws away anything still queued, so without a
     * short pause the {@code ERROR SERVER_SHUTDOWN} we just queued would often never make
     * it out. One shared pause, not one per client: waiting on 100 clients in turn would
     * take 100 times as long for no extra benefit.
     */
    public int shutdownGraceMillis = 200;

    /** How many messages a client may send in one quick burst. */
    public int rateBurst = 20;

    /** How long the burst allowance takes to fill back up. */
    public int rateWindowMillis = 10_000;

    /** How many refused messages in a row before we disconnect the client. */
    public int rateViolationsBeforeKick = 20;

    /** Name the server sends in WELCOME. Must not contain a space. */
    public String serverName = "rakesh-chat";

    /** Where the connection log file is written. */
    public Path connectionLogPath = Path.of("logs", "connections.log");

    /**
     * Phase 8. The shared secret that message bodies are encrypted with.
     *
     * <p>{@code null} or blank means <b>no encryption</b>, which is the default so that
     * telnet and every test written before Phase 8 still work. Every client must be given
     * the same passphrase or their messages will not decrypt.
     */
    public String passphrase = null;

    /** Just a friendlier name for {@code new ServerConfig()}. */
    public static ServerConfig defaults() {
        return new ServerConfig();
    }

    // ------------------------------------------------------------------ Phase 9: from a file

    /** Where {@link ChatServer#main} looks for settings unless told otherwise. */
    public static final Path DEFAULT_FILE = Path.of("server.properties");

    /**
     * Phase 9. Reads settings from a {@code .properties} file.
     *
     * <p>A missing file is <b>not</b> an error: you get the defaults, exactly as before
     * Phase 9. A file with a typo in it is different — {@code maxClients=lots} throws,
     * because starting with a silently wrong limit is worse than not starting at all.
     *
     * <p>Any key you leave out keeps its default, so the file only has to mention what you
     * actually want to change.
     */
    public static ServerConfig load(Path file) throws IOException {
        ServerConfig config = new ServerConfig();
        if (Files.notExists(file)) {
            return config;
        }

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        config.port = intValue(props, "port", config.port);
        config.maxClients = intValue(props, "maxClients", config.maxClients);
        config.maxLineBytes = intValue(props, "maxLineBytes", config.maxLineBytes);
        config.outboxCapacity = intValue(props, "outboxCapacity", config.outboxCapacity);
        config.handshakeTimeoutMillis =
                intValue(props, "handshakeTimeoutMillis", config.handshakeTimeoutMillis);
        config.idleTimeoutMillis = intValue(props, "idleTimeoutMillis", config.idleTimeoutMillis);
        config.pingIntervalMillis = intValue(props, "pingIntervalMillis", config.pingIntervalMillis);
        config.missedPongsBeforeKick =
                intValue(props, "missedPongsBeforeKick", config.missedPongsBeforeKick);
        config.shutdownGraceMillis =
                intValue(props, "shutdownGraceMillis", config.shutdownGraceMillis);
        config.rateBurst = intValue(props, "rateBurst", config.rateBurst);
        config.rateWindowMillis = intValue(props, "rateWindowMillis", config.rateWindowMillis);
        config.rateViolationsBeforeKick =
                intValue(props, "rateViolationsBeforeKick", config.rateViolationsBeforeKick);
        config.serverName = textValue(props, "serverName", config.serverName);
        config.connectionLogPath =
                Path.of(textValue(props, "connectionLogPath",
                        config.connectionLogPath.toString()));

        // A passphrase in a file is a secret in a file. Supported because it is the only
        // way to configure it without a command line, but server.properties is gitignored
        // and ChatServer.main lets the environment override it. See PHASE-9-CONCEPTS.md.
        config.passphrase = textValue(props, "passphrase", config.passphrase);

        return config;
    }

    private static int intValue(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    key + " must be a whole number, was: " + raw.trim());
        }
    }

    private static String textValue(Properties props, String key, String fallback) {
        String raw = props.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }
}
