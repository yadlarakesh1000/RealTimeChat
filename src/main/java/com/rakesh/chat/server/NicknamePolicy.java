package com.rakesh.chat.server;

import java.util.Locale;
import java.util.Set;

/**
 * The rules <i>this server</i> applies to a nickname a client asks for: 3–16 characters,
 * letters/digits/underscore only, and not one of a few reserved names.
 *
 * <p>These are stricter than the rules in {@code Message} (PROTOCOL.md §2.4), and that is
 * on purpose. {@code Message} only asks "can this nickname be written on the wire?", which
 * has to stay loose, because the client uses the same parser to read {@code JOINED} and
 * {@code USERS} lines about other people. If the parser also enforced this server's
 * policy, a client would reject perfectly valid lines about users who really are online.
 *
 * <p>Rejection sends {@code ERROR MALFORMED} and closes the connection, because it happens
 * during the handshake.
 */
public final class NicknamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;

    /**
     * Names that would let a user impersonate the server's own voice in a client UI.
     * Compared case-insensitively — the point is defeated if {@code Admin} slips through.
     */
    private static final Set<String> RESERVED = Set.of("server", "admin", "system", "all");

    private NicknamePolicy() {
        // static-only
    }

    /**
     * @return {@code null} if the nickname is acceptable, otherwise a human-readable
     *         reason phrased to complete the sentence "nickname&nbsp;…" so it can be
     *         dropped straight into an {@code ERROR} line.
     */
    public static String problem(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "must not be empty";
        }
        // Length is counted in chars, not bytes: it is a human-facing display rule, and
        // the byte cost is already bounded by the 4096-byte line cap.
        if (nickname.length() < MIN_LENGTH) {
            return "must be at least " + MIN_LENGTH + " characters";
        }
        if (nickname.length() > MAX_LENGTH) {
            return "must be at most " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < nickname.length(); i++) {
            if (!isAllowed(nickname.charAt(i))) {
                return "must contain only letters, digits and underscore "
                        + "(offending character at index " + i + ")";
            }
        }
        if (RESERVED.contains(nickname.toLowerCase(Locale.ROOT))) {
            return "is reserved";
        }
        return null;
    }

    public static boolean isAcceptable(String nickname) {
        return problem(nickname) == null;
    }

    /**
     * Plain ASCII only, on purpose — not {@link Character#isLetterOrDigit}.
     *
     * <p>{@code isLetterOrDigit} would accept the Cyrillic letter {@code а} (U+0430), which
     * looks exactly like the Latin {@code a} in every font. Then {@code аlice} and
     * {@code alice} would be two different users that nobody can tell apart. The downside
     * is real: this rule excludes most of the world's alphabets.
     */
    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }
}
