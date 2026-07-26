package com.rakesh.chat.server;

import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.ProtocolException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NicknamePolicyTest {

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {
                "bob",              // exactly MIN_LENGTH
                "alice",
                "Alice",            // case is preserved, not folded
                "a_b",
                "___",
                "user123",
                "0123456789",
                "AbCdEfGhIjKlMnOp"  // exactly MAX_LENGTH
        })
        void acceptable(String nickname) {
            assertNull(NicknamePolicy.problem(nickname),
                    "expected <" + nickname + "> to be acceptable");
            assertTrue(NicknamePolicy.isAcceptable(nickname));
        }
    }

    @Nested
    @DisplayName("rejected")
    class Rejected {

        @ParameterizedTest
        @DisplayName("length")
        @ValueSource(strings = {"", "a", "ab", "AbCdEfGhIjKlMnOpQ"})
        void wrongLength(String nickname) {
            assertNotNull(NicknamePolicy.problem(nickname));
        }

        @Test
        void nullIsRejectedRatherThanThrowing() {
            // The caller is on the untrusted-input path; a NullPointerException there
            // would be a stack trace on the console, which is the one thing PROTOCOL.md
            // promises never happens for peer input.
            assertNotNull(NicknamePolicy.problem(null));
        }

        @ParameterizedTest
        @DisplayName("character set")
        @ValueSource(strings = {
                "al ice",     // space  (also illegal at the protocol layer)
                "al,ice",     // comma  (also illegal at the protocol layer)
                "al-ice",     // hyphen: legal on the wire, rejected by policy
                "al.ice",
                "al@ice",
                "ali/ce",
                "señor",      // non-ASCII letter
                "алиса",      // Cyrillic — renders like Latin, which is the whole problem
                "ali\tce"
        })
        void disallowedCharacters(String nickname) {
            assertNotNull(NicknamePolicy.problem(nickname),
                    "expected <" + nickname + "> to be rejected");
        }

        @ParameterizedTest
        @DisplayName("reserved names, case-insensitively")
        @ValueSource(strings = {"server", "SERVER", "Admin", "system", "all", "ALL"})
        void reserved(String nickname) {
            String problem = NicknamePolicy.problem(nickname);
            assertNotNull(problem);
            assertTrue(problem.contains("reserved"), problem);
        }
    }

    @Nested
    @DisplayName("policy is strictly narrower than protocol syntax")
    class PolicyVersusSyntax {

        /**
         * The load-bearing claim of the whole design: anything the policy accepts is also
         * representable on the wire. If this ever fails, the server can hand out a
         * nickname it cannot then transmit — {@code Message.joined(nick)} would throw
         * {@code IllegalArgumentException} from inside a broadcast.
         */
        @ParameterizedTest
        @ValueSource(strings = {"bob", "Alice", "user_123", "AbCdEfGhIjKlMnOp", "___"})
        void everythingThePolicyAllowsIsAlsoValidOnTheWire(String nickname) {
            assertNull(NicknamePolicy.problem(nickname));
            assertDoesNotThrow(() -> Message.joined(nickname).serialize());
        }

        /**
         * And the converse, which is why the two checks cannot be merged: the wire happily
         * carries names this server would never issue. A client must be able to parse
         * them — it may be talking to a server with a different policy, or a future
         * version of this one.
         */
        @ParameterizedTest
        @ValueSource(strings = {"ab", "al-ice", "señor", "алиса",
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}) // 32 chars: the protocol maximum
        void theWireCarriesNamesThisServerWouldNotIssue(String nickname) {
            assertNotNull(NicknamePolicy.problem(nickname),
                    "this test only makes sense for policy-rejected names");
            assertDoesNotThrow(() -> {
                String line = Message.joined(nickname).serialize();
                assertEquals(nickname, Message.parse(line).sender());
            });
        }

        @Test
        @DisplayName("a 33-character name fails at the protocol layer, before policy")
        void syntaxStillHasTheLastWordOnLength() {
            String tooLong = "x".repeat(33);
            assertThrows(ProtocolException.class,
                    () -> Message.parse("HELLO 1 " + tooLong));
        }
    }
}
