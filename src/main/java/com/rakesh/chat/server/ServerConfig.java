package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;

import java.nio.file.Path;

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

    /** After HELLO, drop a client that sends nothing at all for this long (15 minutes). */
    public int idleTimeoutMillis = 900_000;

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

    /** Just a friendlier name for {@code new ServerConfig()}. */
    public static ServerConfig defaults() {
        return new ServerConfig();
    }
}
