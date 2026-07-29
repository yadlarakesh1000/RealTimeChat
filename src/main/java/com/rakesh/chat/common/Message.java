package com.rakesh.chat.common;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


public record Message(MessageType type, String sender, String target,
                      String body, Instant timestamp) {

    /** The only protocol version this build speaks. Strict match — see PROTOCOL.md §2.6. */
    public static final String VERSION = "1";

    /** PROTOCOL.md §2.4. No comma (USERS is comma-joined), no space (non-terminal field). */
    public static final int MAX_NICKNAME_LENGTH = 32;

    /** Cap on the human-readable text of an ERROR line, to keep our own output bounded. */
    private static final int MAX_ERROR_TEXT_LENGTH = 200;

    private static final Map<String, MessageType> VERBS =
            Arrays.stream(MessageType.values())
                  .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

   
    public Message {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }

        switch (type) {
            case HELLO -> {
                requireNickname(sender, "HELLO.sender");
                requireAbsent(target, "HELLO.target");
                requireAbsent(body, "HELLO.body");
                requireAbsent(timestamp, "HELLO.timestamp");
            }
            case MSG -> {
                requireAbsent(sender, "MSG.sender");
                requireAbsent(target, "MSG.target");
                requireNonEmptyText(body, "MSG.body");
                requireAbsent(timestamp, "MSG.timestamp");
            }
            case PM -> {
                requireAbsent(sender, "PM.sender");
                requireNickname(target, "PM.target");
                requireNonEmptyText(body, "PM.body");
                requireAbsent(timestamp, "PM.timestamp");
            }
            // Phase 6. REPLY carries only text; the server remembers who to send it to.
            case REPLY -> {
                requireAbsent(sender, "REPLY.sender");
                requireAbsent(target, "REPLY.target");
                requireNonEmptyText(body, "REPLY.body");
                requireAbsent(timestamp, "REPLY.timestamp");
            }
            // Phase 9 adds PING and PONG to this group. A heartbeat carries no information
            // at all — the fact that it arrived is the whole message.
            case LIST, QUIT, PING, PONG -> {
                requireAbsent(sender, type + ".sender");
                requireAbsent(target, type + ".target");
                requireAbsent(body, type + ".body");
                requireAbsent(timestamp, type + ".timestamp");
            }
            case WELCOME -> {
                requireNickname(sender, "WELCOME.sender");
                requireAbsent(target, "WELCOME.target");
                requireNonEmptyText(body, "WELCOME.body");
                // serverName is a non-terminal field, so a space in it would not survive
                // the round trip. Reject it here rather than discovering it in production.
                if (body.indexOf(' ') >= 0) {
                    throw new IllegalArgumentException(
                            "WELCOME.body (server name) must not contain a space: " + body);
                }
                requireAbsent(timestamp, "WELCOME.timestamp");
            }
            case ERROR -> {
                requireAbsent(sender, "ERROR.sender");
                requireNonEmptyText(target, "ERROR.target");
                try {
                    ErrorCode.valueOf(target);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "ERROR.target must be an ErrorCode constant name, was: " + target);
                }
                requireNonEmptyText(body, "ERROR.body");
                requireAbsent(timestamp, "ERROR.timestamp");
            }
            case CHAT, WHISPER -> {
                requireNickname(sender, type + ".sender");
                requireAbsent(target, type + ".target");
                requireNonEmptyText(body, type + ".body");
                if (timestamp == null) {
                    throw new IllegalArgumentException(type + ".timestamp must not be null");
                }
            }
            // Phase 6. The one server verb that uses target: it names who you sent to.
            case SENT -> {
                requireAbsent(sender, "SENT.sender");
                requireNickname(target, "SENT.target");
                requireNonEmptyText(body, "SENT.body");
                if (timestamp == null) {
                    throw new IllegalArgumentException("SENT.timestamp must not be null");
                }
            }
            case JOINED, LEFT -> {
                requireNickname(sender, type + ".sender");
                requireAbsent(target, type + ".target");
                requireAbsent(body, type + ".body");
                requireAbsent(timestamp, type + ".timestamp");
            }
            case USERS -> {
                requireAbsent(sender, "USERS.sender");
                requireAbsent(target, "USERS.target");
                if (body == null) {
                    throw new IllegalArgumentException("USERS.body must not be null");
                }
             
                requireNoContentControlChars(body, "USERS.body");
                requireAbsent(timestamp, "USERS.timestamp");
            }
        }
    }

    private static void requireAbsent(Object value, String what) {
        if (value != null) {
            throw new IllegalArgumentException(what + " must be null, was: " + value);
        }
    }

    private static void requireNonEmptyText(String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        requireNoContentControlChars(value, what);
    }

    private static void requireNickname(String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        String problem = nicknameProblem(value);
        if (problem != null) {
            throw new IllegalArgumentException(what + " " + problem + ": " + value);
        }
    }

    private static String nicknameProblem(String nickname) {
        if (nickname.isEmpty()) {
            return "must not be empty";
        }
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            return "must be at most " + MAX_NICKNAME_LENGTH + " characters";
        }
        if (nickname.indexOf(' ') >= 0) {
            return "must not contain a space";
        }
        if (nickname.indexOf(',') >= 0) {
            return "must not contain a comma";
        }
        for (int i = 0; i < nickname.length(); i++) {
            if (isControl(nickname.charAt(i))) {
                return "must not contain control characters";
            }
        }
        return null;
    }

    private static void requireNoContentControlChars(String value, String what) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                continue; // framing concern, checked in serialize()
            }
            if (isControl(c)) {
                throw new IllegalArgumentException(String.format(
                        "%s must not contain control characters (found U+%04X at index %d)",
                        what, (int) c, i));
            }
        }
    }


    private static boolean isControl(char c) {
        return (c < 0x20 && c != '\t') || c == 0x7F;
    }


    public String serialize() {
        String line = switch (type) {
            case HELLO   -> "HELLO " + VERSION + " " + sender;
            case MSG     -> "MSG " + body;
            case PM      -> "PM " + target + " " + body;
            case REPLY   -> "REPLY " + body;
            case LIST    -> "LIST";
            case QUIT    -> "QUIT";
            case PING    -> "PING";
            case PONG    -> "PONG";
            case WELCOME -> "WELCOME " + sender + " " + body;
            case ERROR   -> "ERROR " + target + " " + body;
            case CHAT    -> "CHAT " + sender + " " + timestamp + " " + body;
            case WHISPER -> "WHISPER " + sender + " " + timestamp + " " + body;
            case SENT    -> "SENT " + target + " " + timestamp + " " + body;
            case JOINED  -> "JOINED " + sender;
            case LEFT    -> "LEFT " + sender;
           
            case USERS   -> body.isEmpty() ? "USERS" : "USERS " + body;
        };

        int bad = indexOfLineBreak(line);
        if (bad >= 0) {
            throw new IllegalStateException(String.format(
                    "serialized %s would contain a line break (U+%04X at index %d); "
                            + "this would corrupt framing",
                    type, (int) line.charAt(bad), bad));
        }
        return line;
    }

    private static int indexOfLineBreak(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

 
    public static Message parse(String line) throws ProtocolException {
        if (line == null || line.isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED, "empty line");
        }
        rejectControlChars(line);

        int firstSpace = line.indexOf(' ');
        String verb = (firstSpace < 0) ? line : line.substring(0, firstSpace);

        MessageType type = VERBS.get(verb); // case-SENSITIVE, PROTOCOL.md §2
        if (type == null) {
            throw new ProtocolException(ErrorCode.MALFORMED, "unknown verb: " + verb);
        }

        return switch (type) {
            case HELLO   -> parseHello(line);
            case MSG     -> new Message(MessageType.MSG, null, null, requireBody(line, 2, "MSG"), null);
            case PM      -> parsePm(line);
            case REPLY   -> new Message(MessageType.REPLY, null, null, requireBody(line, 2, "REPLY"), null);
            case LIST    -> new Message(MessageType.LIST, null, null, null, null); // must-ignore trailing
            case QUIT    -> new Message(MessageType.QUIT, null, null, null, null); // must-ignore trailing
            case PING    -> new Message(MessageType.PING, null, null, null, null); // must-ignore trailing
            case PONG    -> new Message(MessageType.PONG, null, null, null, null); // must-ignore trailing
            case WELCOME -> parseWelcome(line);
            case ERROR   -> parseError(line);
            case CHAT    -> parseTimestamped(line, MessageType.CHAT);
            case WHISPER -> parseTimestamped(line, MessageType.WHISPER);
            case SENT    -> parseSent(line);
            case JOINED  -> parseNickOnly(line, MessageType.JOINED);
            case LEFT    -> parseNickOnly(line, MessageType.LEFT);
            case USERS   -> parseUsers(line);
        };
    }

    private static Message parseHello(String line) throws ProtocolException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    "HELLO requires a version and a nickname");
        }
        String version = parts[1];
        if (!VERSION.equals(version)) {
            // Checked before the nickname: a version mismatch makes everything that
            // follows unreliable, so it is the more informative failure to report.
            throw new ProtocolException(ErrorCode.BAD_VERSION,
                    "unsupported protocol version: " + version
                            + " (this server speaks " + VERSION + ")");
        }
        String nickname = firstToken(parts[2]); // must-ignore rule, PROTOCOL.md §2.2
        return new Message(MessageType.HELLO, checkedNickname(nickname, "nickname"),
                null, null, null);
    }

    private static Message parsePm(String line) throws ProtocolException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    "PM requires a target and a non-empty body");
        }
        if (parts[2].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED, "PM requires a non-empty body");
        }
        return new Message(MessageType.PM, null,
                checkedNickname(parts[1], "PM target"), parts[2], null);
    }

    private static Message parseWelcome(String line) throws ProtocolException {
        String[] parts = line.split(" ", 4); // 4th part discarded: must-ignore rule
        if (parts.length < 3) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    "WELCOME requires a nickname and a server name");
        }
        if (parts[2].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED, "WELCOME requires a server name");
        }
        return new Message(MessageType.WELCOME, checkedNickname(parts[1], "WELCOME nickname"),
                null, parts[2], null);
    }

    private static Message parseError(String line) throws ProtocolException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3 || parts[2].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    "ERROR requires a code and a description");
        }
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            // Deliberate forward-compat limitation; see PROTOCOL.md §3.
            throw new ProtocolException(ErrorCode.MALFORMED, "unknown error code: " + parts[1]);
        }
        return new Message(MessageType.ERROR, null, code.name(), parts[2], null);
    }

    private static Message parseTimestamped(String line, MessageType type) throws ProtocolException {
        String[] parts = line.split(" ", 4);
        if (parts.length < 4 || parts[3].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    type + " requires a sender, a timestamp and a non-empty body");
        }
        Instant ts;
        try {
            ts = Instant.parse(parts[2]);
        } catch (DateTimeParseException e) {
            throw new ProtocolException(ErrorCode.MALFORMED, "invalid timestamp: " + parts[2]);
        }
        return new Message(type, checkedNickname(parts[1], type + " sender"), null, parts[3], ts);
    }

    /** Phase 6: {@code SENT <target> <timestamp> <text>} — same shape as CHAT/WHISPER. */
    private static Message parseSent(String line) throws ProtocolException {
        String[] parts = line.split(" ", 4);
        if (parts.length < 4 || parts[3].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    "SENT requires a target, a timestamp and a non-empty body");
        }
        Instant ts;
        try {
            ts = Instant.parse(parts[2]);
        } catch (DateTimeParseException e) {
            throw new ProtocolException(ErrorCode.MALFORMED, "invalid timestamp: " + parts[2]);
        }
        return new Message(MessageType.SENT, null, checkedNickname(parts[1], "SENT target"),
                parts[3], ts);
    }

    private static Message parseNickOnly(String line, MessageType type) throws ProtocolException {
        String[] parts = line.split(" ", 2);
        if (parts.length < 2) {
            throw new ProtocolException(ErrorCode.MALFORMED, type + " requires a nickname");
        }
        String nickname = firstToken(parts[1]); // must-ignore rule
        return new Message(type, checkedNickname(nickname, type + " nickname"), null, null, null);
    }

    private static Message parseUsers(String line) {
        String[] parts = line.split(" ", 2);
        // Bare "USERS" means nobody online; it is the round-trip partner of an empty body.
        return new Message(MessageType.USERS, null, null,
                parts.length < 2 ? "" : parts[1], null);
    }

    private static String requireBody(String line, int limit, String verb) throws ProtocolException {
        String[] parts = line.split(" ", limit);
        if (parts.length < limit || parts[limit - 1].isEmpty()) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    verb + " requires a non-empty body");
        }
        return parts[limit - 1];
    }

    /** Everything up to the first space. The remainder is discarded per the must-ignore rule. */
    private static String firstToken(String s) {
        int space = s.indexOf(' ');
        return (space < 0) ? s : s.substring(0, space);
    }

 
    private static String checkedNickname(String nickname, String what) throws ProtocolException {
        String problem = nicknameProblem(nickname);
        if (problem != null) {
            throw new ProtocolException(ErrorCode.MALFORMED, what + " " + problem);
        }
        return nickname;
    }

    /** Parse-time check: on the wire, {@code \n} and {@code \r} are illegal too. */
    private static void rejectControlChars(String line) throws ProtocolException {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (isControl(c) || c == '\n' || c == '\r') {
                throw new ProtocolException(ErrorCode.MALFORMED, String.format(
                        "control character U+%04X at index %d is not permitted", (int) c, i));
            }
        }
    }

   

    public static Message hello(String nickname) {
        return new Message(MessageType.HELLO, nickname, null, null, null);
    }

    public static Message msg(String body) {
        return new Message(MessageType.MSG, null, null, body, null);
    }

    public static Message pm(String target, String body) {
        return new Message(MessageType.PM, null, target, body, null);
    }

    /** Phase 6: reply to whoever whispered to you last. */
    public static Message reply(String body) {
        return new Message(MessageType.REPLY, null, null, body, null);
    }

    public static Message list() {
        return new Message(MessageType.LIST, null, null, null, null);
    }

    public static Message quit() {
        return new Message(MessageType.QUIT, null, null, null, null);
    }

    /** Phase 9: the server asking a quiet client whether it is still there. */
    public static Message ping() {
        return new Message(MessageType.PING, null, null, null, null);
    }

    /** Phase 9: the client's answer to a PING. */
    public static Message pong() {
        return new Message(MessageType.PONG, null, null, null, null);
    }

    public static Message welcome(String nickname, String serverName) {
        return new Message(MessageType.WELCOME, nickname, null, serverName, null);
    }

    public static Message chat(String sender, String body) {
        return new Message(MessageType.CHAT, sender, null, body, Instant.now());
    }

    public static Message chat(String sender, String body, Instant timestamp) {
        return new Message(MessageType.CHAT, sender, null, body, timestamp);
    }

    /** Phase 6: what the receiver of a private message sees. */
    public static Message whisper(String sender, String body) {
        return new Message(MessageType.WHISPER, sender, null, body, Instant.now());
    }

    /** Phase 6: what the sender of a private message sees, as confirmation. */
    public static Message sent(String target, String body) {
        return new Message(MessageType.SENT, null, target, body, Instant.now());
    }

    public static Message joined(String nickname) {
        return new Message(MessageType.JOINED, nickname, null, null, null);
    }

    public static Message left(String nickname) {
        return new Message(MessageType.LEFT, nickname, null, null, null);
    }

    public static Message error(ErrorCode code, String text) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        String safe = sanitize(text);
        if (safe.isEmpty()) {
            safe = code.name();
        }
        return new Message(MessageType.ERROR, null, code.name(), safe, null);
    }

    public static Message users(Collection<String> nicknames) {
        String joined = (nicknames == null) ? "" : String.join(",", nicknames);
        return new Message(MessageType.USERS, null, null, joined, null);
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clipped = text.length() > MAX_ERROR_TEXT_LENGTH
                ? text.substring(0, MAX_ERROR_TEXT_LENGTH)
                : text;
        StringBuilder sb = new StringBuilder(clipped.length());
        for (int i = 0; i < clipped.length(); i++) {
            char c = clipped.charAt(i);
            sb.append((isControl(c) || c == '\n' || c == '\r') ? ' ' : c);
        }
        return sb.toString().trim();
    }

  
    public ErrorCode errorCode() {
        if (type != MessageType.ERROR) {
            throw new IllegalStateException("errorCode() is only valid on ERROR, not " + type);
        }
        return ErrorCode.valueOf(target);
    }

  
    public java.util.List<String> userList() {
        if (type != MessageType.USERS) {
            throw new IllegalStateException("userList() is only valid on USERS, not " + type);
        }
        return body.isEmpty() ? java.util.List.of() : java.util.List.of(body.split(","));
    }
}
