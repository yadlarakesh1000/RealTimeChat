package com.rakesh.chat.common;


public enum ErrorCode {


    NICK_TAKEN,

    BAD_VERSION,


    MALFORMED,

    NO_SUCH_USER,


    TOO_LONG,

    RATE_LIMITED,

    /**
     * A deadline expired: no {@code HELLO} within the handshake window (Phase 5), or —
     * from Phase 9 — no traffic within the idle window.
     *
     * <p>Added in Phase 5. Appending a constant is an <b>additive</b> protocol change, so
     * it does not bump the version (PROTOCOL.md §3): a version-1 peer that has never heard
     * of {@code TIMEOUT} reports the line as {@code MALFORMED} instead of silently
     * ignoring it, which is the documented forward-compatibility limitation and is
     * harmless here because every {@code TIMEOUT} is immediately followed by a close.
     * <b>Renaming or reordering</b> a constant would be breaking; adding one is not.
     */
    TIMEOUT
}
