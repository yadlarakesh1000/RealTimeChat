package com.rakesh.chat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure functions, no sockets, no threads, no sleeps. The whole class runs in
 * milliseconds, which is what makes it worth running on every save.
 */
class MessageTest {

    private static final Instant TS = Instant.parse("2026-07-26T09:14:02.881143100Z");

    private static Message parseOk(String line) {
        try {
            return Message.parse(line);
        } catch (ProtocolException e) {
            throw new AssertionError("expected " + line + " to parse, got "
                    + e.code() + ": " + e.getMessage(), e);
        }
    }

    private static ProtocolException parseFails(String line) {
        return assertThrows(ProtocolException.class, () -> Message.parse(line),
                () -> "expected <" + line + "> to be rejected");
    }

    private static void assertRoundTrip(Message m) {
        String wire = m.serialize();
        assertEquals(m, parseOk(wire),
                () -> "round trip failed for " + m.type() + " via wire: <" + wire + ">");
    }

    // ------------------------------------------------------------------
    // Round trip — the headline contract
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("parse(serialize(m)).equals(m)")
    class RoundTrip {

        @Test
        void everyMessageTypeRoundTrips() {
            List<Message> all = List.of(
                    Message.hello("alice"),
                    Message.msg("hello world"),
                    Message.pm("bob", "hello world"),
                    Message.reply("hello world"),
                    Message.list(),
                    Message.quit(),
                    Message.pong(),
                    Message.welcome("alice", "rakesh-chat"),
                    Message.error(ErrorCode.NICK_TAKEN, "nickname already in use"),
                    Message.chat("alice", "hello world", TS),
                    new Message(MessageType.WHISPER, "alice", null, "psst", TS),
                    new Message(MessageType.SENT, null, "bob", "psst", TS),
                    Message.joined("alice"),
                    Message.left("alice"),
                    Message.users(List.of("alice", "bob")),
                    Message.ping());

            all.forEach(MessageTest::assertRoundTrip);

            // Fail loudly if someone adds a verb and forgets to cover it here.
            assertEquals(
                    java.util.Arrays.stream(MessageType.values()).toList(),
                    all.stream().map(Message::type).toList(),
                    "the round-trip set must cover every MessageType, in declaration order");
        }

        @Test
        void emptyUserListRoundTrips() {
            assertRoundTrip(Message.users(List.of()));
            assertEquals(List.of(), parseOk("USERS").userList());
        }

        @Test
        void nanosecondPrecisionSurvivesRoundTrip() {
            assertRoundTrip(Message.chat("alice", "x",
                    Instant.parse("2026-07-26T09:14:02.000000001Z")));
        }
    }

    // ------------------------------------------------------------------
    // MSG — body handling
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("MSG body")
    class MsgBody {

        @Test
        @DisplayName("body is the full remainder, not just the first word")
        void fullBodyIsPreserved() {
            Message m = parseOk("MSG hello world how are you");
            assertEquals(MessageType.MSG, m.type());
            assertEquals("hello world how are you", m.body());
        }

        @Test
        @DisplayName("a second space is body content, not a delimiter")
        void leadingSpaceInBodyIsPreserved() {
            assertEquals(" hello", parseOk("MSG  hello").body());
        }

        @Test
        void trailingSpacesAreNotTrimmed() {
            assertEquals("hi   ", parseOk("MSG hi   ").body());
        }

        @Test
        void tabIsAllowedInBody() {
            assertEquals("a\tb", parseOk("MSG a\tb").body());
        }

        @Test
        void noBodyAtAllIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("MSG").code());
        }

        @Test
        void emptyBodyIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("MSG ").code());
        }
    }

    // ------------------------------------------------------------------
    // Degenerate and hostile input
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        void emptyLineIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("").code());
        }

        @Test
        void singleSpaceIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails(" ").code());
        }

        @Test
        void nullIsMalformedRatherThanNpe() {
            assertEquals(ErrorCode.MALFORMED, parseFails(null).code());
        }

        @Test
        void unknownVerbIsMalformedAndNamesTheVerb() {
            ProtocolException e = parseFails("FOO bar");
            assertEquals(ErrorCode.MALFORMED, e.code());
            assertTrue(e.getMessage().contains("FOO"), e.getMessage());
        }

        @Test
        @DisplayName("verbs are case-SENSITIVE: 'msg hello' is an unknown verb")
        void lowercaseVerbIsUnknown() {
            ProtocolException e = parseFails("msg hello");
            assertEquals(ErrorCode.MALFORMED, e.code());
            assertTrue(e.getMessage().contains("unknown verb"), e.getMessage());
        }

        @Test
        void mixedCaseVerbIsUnknown() {
            assertEquals(ErrorCode.MALFORMED, parseFails("Msg hello").code());
        }

        @Test
        void leadingSpaceMakesAnEmptyVerb() {
            assertEquals(ErrorCode.MALFORMED, parseFails(" MSG hello").code());
        }

        @Test
        @DisplayName("no input shape throws ArrayIndexOutOfBounds")
        void trailingDelimitersNeverThrowUnchecked() {
            for (String line : List.of("HELLO", "HELLO ", "HELLO 1", "HELLO 1 ",
                                       "PM", "PM ", "PM bob", "PM bob ",
                                       "CHAT", "CHAT a", "CHAT a b", "CHAT a b ",
                                       "ERROR", "ERROR MALFORMED", "ERROR MALFORMED ",
                                       "WELCOME", "WELCOME a", "WELCOME a ",
                                       "JOINED", "JOINED ", "LEFT", "LEFT ", "USERS ")) {
                assertDoesNotThrow(() -> {
                    try {
                        Message.parse(line);
                    } catch (ProtocolException expected) {
                        // a checked rejection is fine; an unchecked throw is not
                    }
                }, () -> "unchecked throw on <" + line + ">");
            }
        }

        @Test
        void controlCharactersInBodyAreRejected() {
            // NUL, BEL, ESC, DEL — built with casts so no source-encoding step can
            // quietly normalise them away.
            for (int c : new int[] {0x00, 0x07, 0x1B, 0x7F}) {
                String line = "MSG a" + (char) c + "b";
                assertEquals(ErrorCode.MALFORMED, parseFails(line).code(),
                        () -> "U+" + Integer.toHexString(c) + " must be rejected");
            }
        }

        @Test
        @DisplayName("a stray CR is the reader's to strip; parse rejects it")
        void carriageReturnInLineIsRejected() {
            assertEquals(ErrorCode.MALFORMED, parseFails("MSG hi\r").code());
            assertEquals(ErrorCode.MALFORMED, parseFails("MSG a\rb").code());
        }
    }

    // ------------------------------------------------------------------
    // PM
    // ------------------------------------------------------------------

    @Nested
    class Pm {

        @Test
        void targetAndBodyAreSplitOnce() {
            Message m = parseOk("PM alice hello world");
            assertEquals(MessageType.PM, m.type());
            assertEquals("alice", m.target());
            assertEquals("hello world", m.body());
            assertNull(m.sender());
        }

        @Test
        void noBodyIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("PM alice").code());
        }

        @Test
        void emptyBodyIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("PM alice ").code());
        }

        @Test
        void noTargetIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("PM").code());
        }
    }

    // ------------------------------------------------------------------
    // UTF-8
    // ------------------------------------------------------------------

    @Nested
    class Utf8 {

        @Test
        @DisplayName("emoji survive the round trip (multi-byte, surrogate pairs)")
        void emojiSurviveRoundTrip() {
            String body = "hello 👋🏽 world 🎉 café";
            Message m = Message.msg(body);
            assertRoundTrip(m);
            assertEquals(body, parseOk(m.serialize()).body());
        }

        @Test
        void nonLatinNicknamesAreAccepted() {
            assertEquals("ラケシュ", parseOk("HELLO 1 ラケシュ").sender());
        }

        @Test
        @DisplayName("the nickname cap is in characters and is exact at the boundary")
        void nicknameLengthIsCappedAtTheBoundary() {
            String nick = "a".repeat(Message.MAX_NICKNAME_LENGTH);
            assertEquals(nick, parseOk("HELLO 1 " + nick).sender());
            assertEquals(ErrorCode.MALFORMED, parseFails("HELLO 1 " + nick + "a").code());
        }
    }

    // ------------------------------------------------------------------
    // serialize() framing guard
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("serialize() framing guard")
    class FramingGuard {

        @Test
        @DisplayName("a newline in the body is an IllegalStateException, not silent corruption")
        void newlineInBodyThrowsIllegalState() {
            Message m = Message.msg("hello\nworld");
            IllegalStateException e = assertThrows(IllegalStateException.class, m::serialize);
            assertTrue(e.getMessage().contains("line break"), e.getMessage());
        }

        @Test
        void carriageReturnInBodyThrowsIllegalState() {
            assertThrows(IllegalStateException.class, () -> Message.msg("a\rb").serialize());
        }

        @Test
        void newlineInChatBodyThrowsIllegalState() {
            assertThrows(IllegalStateException.class,
                    () -> Message.chat("alice", "a\nb", TS).serialize());
        }

        @Test
        @DisplayName("no serialized form of a valid message contains a line break")
        void validMessagesNeverContainLineBreaks() {
            for (Message m : List.of(Message.hello("a"), Message.msg("b"), Message.list(),
                                     Message.quit(), Message.chat("a", "b", TS),
                                     Message.error(ErrorCode.TOO_LONG, "x"),
                                     Message.users(List.of("a", "b")))) {
                String wire = m.serialize();
                assertFalse(wire.contains("\n") || wire.contains("\r"), wire);
            }
        }

        @Test
        @DisplayName("other control characters are rejected at construction, not at serialize")
        void otherControlCharsAreRejectedByTheConstructor() {
            assertThrows(IllegalArgumentException.class,
                    () -> Message.msg("a" + (char) 0x07 + "b"));
            assertThrows(IllegalArgumentException.class,
                    () -> Message.msg("a" + (char) 0x00 + "b"));
        }
    }

    // ------------------------------------------------------------------
    // HELLO / version
    // ------------------------------------------------------------------

    @Nested
    class Handshake {

        @Test
        void versionOneIsAccepted() {
            Message m = parseOk("HELLO 1 bob");
            assertEquals(MessageType.HELLO, m.type());
            assertEquals("bob", m.sender());
        }

        @Test
        @DisplayName("HELLO 99 bob -> BAD_VERSION, not MALFORMED")
        void wrongVersionIsBadVersion() {
            ProtocolException e = parseFails("HELLO 99 bob");
            assertEquals(ErrorCode.BAD_VERSION, e.code());
            assertTrue(e.getMessage().contains("99"), e.getMessage());
        }

        @Test
        void nonNumericVersionIsBadVersion() {
            assertEquals(ErrorCode.BAD_VERSION, parseFails("HELLO x bob").code());
        }

        @Test
        @DisplayName("version is checked before the nickname")
        void badVersionWinsOverBadNickname() {
            assertEquals(ErrorCode.BAD_VERSION, parseFails("HELLO 2 " + "x".repeat(99)).code());
        }

        @Test
        void missingNicknameIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("HELLO 1").code());
            assertEquals(ErrorCode.MALFORMED, parseFails("HELLO 1 ").code());
        }

        @Test
        void nicknameWithACommaIsRejected() {
            assertEquals(ErrorCode.MALFORMED, parseFails("HELLO 1 a,b").code());
        }
    }

    // ------------------------------------------------------------------
    // Must-ignore rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("must-ignore rule (additive versioning)")
    class MustIgnore {

        @Test
        void helloIgnoresTrailingFields() {
            assertEquals("bob", parseOk("HELLO 1 bob future-field and-another").sender());
        }

        @Test
        void listAndQuitIgnoreEverythingAfterTheVerb() {
            assertEquals(MessageType.LIST, parseOk("LIST anything at all").type());
            assertEquals(MessageType.QUIT, parseOk("QUIT now please").type());
            assertEquals(Message.list(), parseOk("LIST whatever"));
        }

        @Test
        void joinedAndLeftIgnoreTrailingFields() {
            assertEquals("bob", parseOk("JOINED bob 2026-07-26T00:00:00Z").sender());
            assertEquals("bob", parseOk("LEFT bob because reasons").sender());
        }

        @Test
        void welcomeIgnoresTrailingFields() {
            Message m = parseOk("WELCOME alice rakesh-chat extra stuff");
            assertEquals("alice", m.sender());
            assertEquals("rakesh-chat", m.body());
        }

        @Test
        @DisplayName("terminal fields absorb the remainder instead of ignoring it")
        void terminalFieldsAreNotSubjectToMustIgnore() {
            assertEquals("some long text here",
                    parseOk("ERROR MALFORMED some long text here").body());
            assertEquals("a b c", parseOk("CHAT alice " + TS + " a b c").body());
        }
    }

    // ------------------------------------------------------------------
    // Server-to-client verbs
    // ------------------------------------------------------------------

    @Nested
    class ServerToClient {

        @Test
        void chatCarriesSenderTimestampAndBody() {
            Message m = parseOk("CHAT alice " + TS + " hello world how are you");
            assertEquals("alice", m.sender());
            assertEquals(TS, m.timestamp());
            assertEquals("hello world how are you", m.body());
        }

        @Test
        void invalidTimestampIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("CHAT alice not-a-time hello").code());
        }

        @Test
        void errorExposesItsCode() {
            Message m = parseOk("ERROR NICK_TAKEN nickname already in use: alice");
            assertEquals(ErrorCode.NICK_TAKEN, m.errorCode());
            assertEquals("nickname already in use: alice", m.body());
        }

        @Test
        @DisplayName("an unknown ErrorCode is MALFORMED, not passed through")
        void unknownErrorCodeIsMalformed() {
            assertEquals(ErrorCode.MALFORMED, parseFails("ERROR WAT something happened").code());
        }

        @Test
        void usersSplitsOnCommas() {
            assertEquals(List.of("alice", "bob", "carol"),
                    parseOk("USERS alice,bob,carol").userList());
        }

        @Test
        void directionIsQueryable() {
            assertTrue(MessageType.HELLO.fromClient());
            assertTrue(MessageType.MSG.fromClient());
            assertFalse(MessageType.WELCOME.fromClient());
            assertFalse(MessageType.CHAT.fromClient());
            for (MessageType t : MessageType.values()) {
                assertNotEquals(t.fromClient(), t.fromServer(),
                        () -> t + " must belong to exactly one direction");
            }
        }
    }

    // ------------------------------------------------------------------
    // Factories and the field table
    // ------------------------------------------------------------------

    @Nested
    class Factories {

        @Test
        void chatAssignsTheTimestampItself() {
            Instant before = Instant.now();
            Message m = Message.chat("alice", "hi");
            assertNotNull(m.timestamp());
            assertFalse(m.timestamp().isBefore(before));
        }

        @Test
        @DisplayName("error() sanitises rather than throwing while reporting an error")
        void errorSanitisesItsText() {
            Message m = Message.error(ErrorCode.MALFORMED, "bad\nline here");
            assertDoesNotThrow(m::serialize);
            assertEquals("bad line here", m.body());
        }

        @Test
        void errorFallsBackToTheCodeNameWhenTextIsBlank() {
            assertEquals("MALFORMED", Message.error(ErrorCode.MALFORMED, "   ").body());
            assertEquals("TOO_LONG", Message.error(ErrorCode.TOO_LONG, null).body());
        }

        @Test
        void errorTextIsTruncated() {
            Message m = Message.error(ErrorCode.MALFORMED, "x".repeat(5000));
            assertTrue(m.serialize().length() < 300, "error text must stay bounded");
        }

        @Test
        void usersHandlesNullAndEmpty() {
            assertEquals("", Message.users(null).body());
            assertEquals("", Message.users(List.of()).body());
        }

        @Test
        @DisplayName("the field table is enforced, not merely documented")
        void fieldTableViolationsAreRejected() {
            // body present where the table says it must be absent
            assertThrows(IllegalArgumentException.class,
                    () -> new Message(MessageType.LIST, null, null, "x", null));
            // sender missing where it is required
            assertThrows(IllegalArgumentException.class,
                    () -> new Message(MessageType.JOINED, null, null, null, null));
            // timestamp missing on CHAT
            assertThrows(IllegalArgumentException.class,
                    () -> new Message(MessageType.CHAT, "alice", null, "hi", null));
            // timestamp present on MSG
            assertThrows(IllegalArgumentException.class,
                    () -> new Message(MessageType.MSG, null, null, "hi", TS));
            // ERROR.target must be a real code
            assertThrows(IllegalArgumentException.class,
                    () -> new Message(MessageType.ERROR, null, "NOPE", "text", null));
            // WELCOME server name must not contain a space (it is a non-terminal field)
            assertThrows(IllegalArgumentException.class,
                    () -> Message.welcome("alice", "my server"));
        }

        @Test
        void typedAccessorsRejectTheWrongType() {
            assertThrows(IllegalStateException.class, () -> Message.msg("hi").errorCode());
            assertThrows(IllegalStateException.class, () -> Message.msg("hi").userList());
        }
    }
}
