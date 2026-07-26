package com.rakesh.chat.server;

import java.util.Locale;
import java.util.Set;

/**
 * Server <b>policy</b> on nicknames, as distinct from protocol <b>syntax</b>.
 *
 * <p>This split is the whole point of the class, and it is worth being precise about:
 *
 * <table border="1">
 *   <caption>Two different questions about the same string</caption>
 *   <tr><th></th><th>{@code Message}/PROTOCOL.md §2.4</th><th>{@code NicknamePolicy}</th></tr>
 *   <tr><td>Question</td>
 *       <td>Can this string be <i>represented</i> on the wire?</td>
 *       <td>Will <i>this server</i> hand it out?</td></tr>
 *   <tr><td>Rule</td>
 *       <td>non-empty, ≤32 chars, no space, no comma, no control char</td>
 *       <td>3–16 chars, {@code [A-Za-z0-9_]} only, not reserved</td></tr>
 *   <tr><td>Applies to</td>
 *       <td>every nickname field in every verb, both directions</td>
 *       <td>only the nickname a client <i>requests</i> in {@code HELLO}</td></tr>
 *   <tr><td>Lives in</td><td>{@code common}</td><td>{@code server}</td></tr>
 * </table>
 *
 * <p><b>Why they cannot be the same check.</b> The syntax rule has to be the looser one,
 * because the client parses {@code JOINED}, {@code CHAT} and {@code USERS} with the same
 * code. If {@code Message.parse} enforced the policy, then tightening the policy on the
 * server — or federating with a server that allows 20-character names — would make
 * clients reject perfectly well-formed lines describing users who are demonstrably
 * online. Policy belongs to whoever issues the resource; syntax belongs to the wire.
 *
 * <p>Rejection is reported as {@code ERROR MALFORMED}, and it closes the connection,
 * because it happens during the handshake and the handshake either completes or the
 * connection ends (PROTOCOL.md §3).
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
     * ASCII-only by design, and explicitly <i>not</i> {@link Character#isLetterOrDigit}.
     *
     * <p>{@code isLetterOrDigit} accepts the whole Unicode letter category, which admits
     * homoglyphs: Cyrillic {@code а} (U+0430) renders identically to Latin {@code a} in
     * every font, so {@code аlice} and {@code alice} would be two visually indistinguishable
     * users. Case-insensitive uniqueness cannot save you from that; only restricting the
     * alphabet can. The cost is real — this rule excludes most of the world's scripts —
     * and the honest fix is Unicode normalisation plus a confusable-skeleton check
     * (UTS&nbsp;#39), which is out of scope here. Saying that out loud is the point.
     */
    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }
}
