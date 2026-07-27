package com.rakesh.chat.server;

import java.time.Instant;
import java.util.Locale;

/**
 * Prints one tidy line to the console for each server event, instead of scattered
 * {@code System.out.println} calls with no timestamp.
 *
 * <pre>
 * 2026-07-26T10:15:30.123456700Z [reader-alice] JOINED         alice             2 online
 * 2026-07-26T10:15:31.007881200Z [reader-bob]   MALFORMED      /127.0.0.1:51544  unknown verb: FOO
 * </pre>
 *
 * <p>A real project would use a logging library such as SLF4J + Logback so the output can
 * be filtered and sent to files. This is a college project with one dependency, and thirty
 * lines here buy the two things that actually matter: a timestamp and a thread name.
 */
final class ServerLog {

    private ServerLog() {
        // static-only
    }

    /** A normal, expected lifecycle event. */
    static void event(String event, String who, String detail) {
        System.out.println(format(event, who, detail));
    }

    /** The peer misbehaved. Expected on an untrusted socket; not our bug. */
    static void warn(String event, String who, String detail) {
        System.out.println(format(event, who, detail));
    }

    /** Our bug, or an environment failure. This is the only kind that deserves stderr. */
    static void error(String event, String who, String detail) {
        System.err.println(format(event, who, detail));
    }

    private static String format(String event, String who, String detail) {
        // 14 = the length of the longest event name (HANDSHAKE_FAIL), so the columns line up.
        return String.format(Locale.ROOT, "%s [%s] %-14s %-24s %s",
                Instant.now(),
                Thread.currentThread().getName(),
                event,
                who == null ? "-" : who,
                detail == null ? "" : detail);
    }
}
