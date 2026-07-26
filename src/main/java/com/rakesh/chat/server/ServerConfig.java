package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;

import java.nio.file.Path;

/**
 * Every tunable number in the server, in one place.
 *
 * <p>Phase 5 introduces this for a reason that has nothing to do with configurability:
 * <b>testability</b>. A 10-second handshake deadline is the right production value and a
 * catastrophic test value — a suite that waits 10 real seconds to prove one branch is a
 * suite nobody runs. Passing the deadline in lets {@code Phase5Test} use 250&nbsp;ms and
 * assert the same code path.
 *
 * <p>Phase 9 turns this into a properties file. Nothing else has to change when it does:
 * only {@link #defaults()} knows where the numbers come from.
 */
public record ServerConfig(
        int port,
        int maxClients,
        int maxLineBytes,
        int outboxCapacity,
        int handshakeTimeoutMillis,
        int idleTimeoutMillis,
        int rateBurst,
        int rateWindowMillis,
        int rateViolationsBeforeKick,
        String serverName,
        Path connectionLogPath) {

    public ServerConfig {
        requirePositive(maxClients, "maxClients");
        requirePositive(maxLineBytes, "maxLineBytes");
        requirePositive(outboxCapacity, "outboxCapacity");
        requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
        requirePositive(idleTimeoutMillis, "idleTimeoutMillis");
        requirePositive(rateBurst, "rateBurst");
        requirePositive(rateWindowMillis, "rateWindowMillis");
        requirePositive(rateViolationsBeforeKick, "rateViolationsBeforeKick");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (serverName == null || serverName.isBlank() || serverName.indexOf(' ') >= 0) {
            // WELCOME carries the server name as a non-terminal field, so a space in it
            // would not survive the round trip. Fail at startup, not on the first client.
            throw new IllegalArgumentException(
                    "serverName must be non-blank and contain no space: " + serverName);
        }
    }

    private static void requirePositive(int value, String what) {
        if (value <= 0) {
            throw new IllegalArgumentException(what + " must be positive, was " + value);
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig(
                5000,                                   // port
                100,                                    // maxClients
                BoundedLineReader.DEFAULT_MAX_BYTES,    // maxLineBytes  (PROTOCOL.md §1)
                256,                                    // outboxCapacity (Phase 3 decision)
                10_000,                                 // handshakeTimeoutMillis (Phase 5)
                900_000,                                // idleTimeoutMillis — 15 min, see below
                20,                                     // rateBurst    — 20 lines...
                10_000,                                 // rateWindowMillis — ...per 10 s
                20,                                     // rateViolationsBeforeKick
                "rakesh-chat",
                Path.of("logs", "connections.log"));
    }

    // --- withers: only the knobs the tests actually need to move -------------

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(newPort, maxClients, maxLineBytes, outboxCapacity,
                handshakeTimeoutMillis, idleTimeoutMillis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withMaxClients(int max) {
        return new ServerConfig(port, max, maxLineBytes, outboxCapacity,
                handshakeTimeoutMillis, idleTimeoutMillis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withHandshakeTimeoutMillis(int millis) {
        return new ServerConfig(port, maxClients, maxLineBytes, outboxCapacity,
                millis, idleTimeoutMillis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withIdleTimeoutMillis(int millis) {
        return new ServerConfig(port, maxClients, maxLineBytes, outboxCapacity,
                handshakeTimeoutMillis, millis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withRateLimit(int burst, int windowMillis, int violationsBeforeKick) {
        return new ServerConfig(port, maxClients, maxLineBytes, outboxCapacity,
                handshakeTimeoutMillis, idleTimeoutMillis, burst, windowMillis,
                violationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withOutboxCapacity(int capacity) {
        return new ServerConfig(port, maxClients, maxLineBytes, capacity,
                handshakeTimeoutMillis, idleTimeoutMillis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, connectionLogPath);
    }

    public ServerConfig withConnectionLogPath(Path path) {
        return new ServerConfig(port, maxClients, maxLineBytes, outboxCapacity,
                handshakeTimeoutMillis, idleTimeoutMillis, rateBurst, rateWindowMillis,
                rateViolationsBeforeKick, serverName, path);
    }
}
