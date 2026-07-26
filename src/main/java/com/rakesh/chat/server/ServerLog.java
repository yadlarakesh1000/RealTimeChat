package com.rakesh.chat.server;

import java.time.Instant;
import java.util.Locale;

/**
 * Structured console logging: one line, fixed columns, always the same five facts.
 *
 * <pre>
 * 2026-07-26T10:15:30.123456700Z [reader-alice]   JOINED  alice                    2 online
 * 2026-07-26T10:15:31.007881200Z [reader-bob]     MALFORMED /127.0.0.1:51544       unknown verb: FOO
 * </pre>
 *
 * <p><b>Why not SLF4J + Logback, which the build guide suggests.</b> A logging facade
 * earns its dependency when libraries in the same process need to bind to the
 * application's logger, when levels have to be reconfigured without a rebuild, or when
 * appenders (rolling files, syslog, JSON) are needed. None of that is true here, and the
 * project has exactly one non-test dependency today — which is the reason a stranger can
 * clone it and run {@code mvn test} offline-ish without a surprise. What Logback would
 * actually buy at this size is the timestamp and the thread name, which is the thirty
 * lines below.
 *
 * <p>The honest caveat, and the interview answer: this is <b>not</b> what I would do in
 * production. It has no levels beyond the three methods here, no way to silence it, and
 * it writes synchronously on the caller's thread. The moment either of those matters,
 * SLF4J's facade is the correct answer precisely <i>because</i> it is a facade — it lets
 * the binding be someone else's decision.
 *
 * <p>{@code System.out}/{@code System.err} are themselves synchronised {@code PrintStream}s,
 * so interleaved lines from many handler threads do not tear. They can still be reordered
 * relative to each other; the timestamp is the ordering authority, not the file position.
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
        // Locale.ROOT: %-24s is locale-independent, but making every format call in the
        // codebase explicit means there is no call site to audit later. The same reflex
        // as specifying UTF-8 on every stream.
        // 14 = the width of the longest event name (HANDSHAKE_FAIL), so the columns line
        // up and a human can scan the "who" column without reading the events.
        return String.format(Locale.ROOT, "%s [%s] %-14s %-24s %s",
                Instant.now(),
                Thread.currentThread().getName(),
                event,
                who == null ? "-" : who,
                detail == null ? "" : detail);
    }
}
