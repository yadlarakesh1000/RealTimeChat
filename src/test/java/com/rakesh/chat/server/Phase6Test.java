package com.rakesh.chat.server;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.rakesh.chat.server.TestSupport.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 — private messaging.
 *
 * <p>The headline test is {@link #carolSeesNothingWhenAliceWhispersToBob()}: three clients,
 * one private message, and the third client's raw socket stays completely silent.
 */
@Timeout(60)
class Phase6Test {

    @TempDir
    Path tmp;

    private ChatServer server;
    private final List<RawClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        ServerConfig config = new ServerConfig();
        config.port = 0;                                            // any free port
        config.connectionLogPath = tmp.resolve("connections.log");  // don't touch logs/
        server = new ChatServer(config);

        Thread acceptor = new Thread(server::start, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(RawClient::close);
        clients.clear();
        server.shutdown();
    }

    /** Connects one client, says HELLO, and remembers it so teardown can close it. */
    private RawClient join(String nickname) throws Exception {
        RawClient client = new RawClient(server.getPort());
        clients.add(client);
        return client.helloAs(nickname);
    }

    /** Reads and throws away {@code count} JOINED lines about other people arriving. */
    private void skipJoinedLines(RawClient client, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            client.expect(MessageType.JOINED);
        }
    }

    // ------------------------------------------------------------ the checkpoint

    @Test
    @DisplayName("A whispers to B; C's raw stream stays completely silent")
    void carolSeesNothingWhenAliceWhispersToBob() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        RawClient carol = join("carol");

        // alice saw bob and carol arrive; bob saw carol arrive; carol saw nobody.
        skipJoinedLines(alice, 2);
        skipJoinedLines(bob, 1);

        alice.send("PM bob meet me at 5");

        // bob receives the whisper
        Message whisper = bob.expect(MessageType.WHISPER);
        assertEquals("alice", whisper.sender());
        assertEquals("meet me at 5", whisper.body());
        assertNotNull(whisper.timestamp(), "the server stamps the time, not the client");

        // alice receives a confirmation that names who she sent it to
        Message confirmation = alice.expect(MessageType.SENT);
        assertEquals("bob", confirmation.target());
        assertEquals("meet me at 5", confirmation.body());

        // carol receives nothing whatsoever. This is the privacy boundary.
        carol.expectNothing(500);
    }

    @Test
    @DisplayName("a private message never appears on the broadcast path")
    void aWhisperIsNotAlsoBroadcast() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        skipJoinedLines(alice, 1);

        alice.send("PM bob secret");
        bob.expect(MessageType.WHISPER);
        alice.expect(MessageType.SENT);

        // If PM leaked into broadcast(), bob would now also have a CHAT line waiting.
        bob.expectNothing(400);
    }

    // ------------------------------------------------------------ routing failures

    @Test
    @DisplayName("PM to somebody who is not online -> ERROR NO_SUCH_USER")
    void offlineTargetIsRefused() throws Exception {
        RawClient alice = join("alice");

        alice.send("PM nobody hello");

        Message err = alice.expectError(ErrorCode.NO_SUCH_USER);
        assertTrue(err.body().contains("nobody"), err.body());
    }

    @Test
    @DisplayName("the error kills the message, not the connection")
    void aFailedPmLeavesTheSessionUsable() throws Exception {
        RawClient alice = join("alice");

        alice.send("PM nobody hello");
        alice.expectError(ErrorCode.NO_SUCH_USER);

        alice.send("LIST");
        assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
    }

    @Test
    @DisplayName("PM to somebody who has just left -> ERROR NO_SUCH_USER")
    void aTargetWhoLeftIsTreatedAsOffline() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        skipJoinedLines(alice, 1);

        bob.send("QUIT");
        bob.expectClosed();
        alice.expect(MessageType.LEFT);

        // Wait until the registry really has forgotten bob, otherwise this test is racing
        // the server rather than testing it.
        await("bob to be removed from the registry",
                () -> server.getRegistry().find("bob").isEmpty());

        alice.send("PM bob are you there");
        alice.expectError(ErrorCode.NO_SUCH_USER);
    }

    // ------------------------------------------------------------ details of the routing

    @Test
    @DisplayName("the target nickname is matched ignoring case, and echoed back as spelled")
    void targetLookupIgnoresCase() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("Bob");
        skipJoinedLines(alice, 1);

        alice.send("PM bOB hello");

        assertEquals("alice", bob.expect(MessageType.WHISPER).sender());

        // The confirmation uses Bob's own spelling, not what alice typed.
        assertEquals("Bob", alice.expect(MessageType.SENT).target());
    }

    @Test
    @DisplayName("the message body keeps its spaces exactly")
    void theBodyIsTakenVerbatim() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        skipJoinedLines(alice, 1);

        alice.send("PM bob  two  spaces  everywhere ");

        assertEquals(" two  spaces  everywhere ", bob.expect(MessageType.WHISPER).body());
    }

    @Test
    @DisplayName("PM to yourself is allowed: you get the whisper and the confirmation")
    void messagingYourselfWorks() throws Exception {
        RawClient alice = join("alice");

        alice.send("PM alice note to self");

        assertEquals("alice", alice.expect(MessageType.WHISPER).sender());
        assertEquals("alice", alice.expect(MessageType.SENT).target());
    }

    @Test
    @DisplayName("PM without a body is malformed, and the connection survives")
    void pmNeedsABody() throws Exception {
        RawClient alice = join("alice");

        alice.send("PM bob");
        assertEquals(ErrorCode.MALFORMED, alice.expect(MessageType.ERROR).errorCode());

        alice.send("LIST");
        assertEquals(MessageType.USERS, alice.read().type());
    }

    // ------------------------------------------------------------ REPLY

    @Test
    @DisplayName("REPLY goes back to whoever whispered last")
    void replyGoesBackToTheLastWhisperer() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        skipJoinedLines(alice, 1);

        alice.send("PM bob hello");
        bob.expect(MessageType.WHISPER);
        alice.expect(MessageType.SENT);

        // bob never types alice's name — the server remembered it for him.
        bob.send("REPLY hello yourself");

        Message back = alice.expect(MessageType.WHISPER);
        assertEquals("bob", back.sender());
        assertEquals("hello yourself", back.body());

        assertEquals("alice", bob.expect(MessageType.SENT).target());
    }

    @Test
    @DisplayName("REPLY before anybody has whispered to you -> ERROR NO_SUCH_USER")
    void replyWithNobodyToReplyToIsRefused() throws Exception {
        RawClient alice = join("alice");

        alice.send("REPLY hello?");

        Message err = alice.expectError(ErrorCode.NO_SUCH_USER);
        assertTrue(err.body().contains("nobody"), err.body());
    }

    @Test
    @DisplayName("the last whisperer is updated by each new whisper")
    void replyFollowsTheMostRecentWhisperer() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        RawClient carol = join("carol");
        skipJoinedLines(alice, 2);
        skipJoinedLines(bob, 1);

        alice.send("PM carol first");
        carol.expect(MessageType.WHISPER);
        alice.expect(MessageType.SENT);

        bob.send("PM carol second");
        carol.expect(MessageType.WHISPER);
        bob.expect(MessageType.SENT);

        // bob whispered most recently, so carol's REPLY must go to bob, not alice.
        carol.send("REPLY got it");

        assertEquals("carol", bob.expect(MessageType.WHISPER).sender());
        assertEquals("bob", carol.expect(MessageType.SENT).target());
        alice.expectNothing(400);
    }

    // ------------------------------------------------------------ nothing else broke

    @Test
    @DisplayName("MSG still reaches everybody else, and still not the sender")
    void broadcastStillWorks() throws Exception {
        RawClient alice = join("alice");
        RawClient bob = join("bob");
        RawClient carol = join("carol");
        skipJoinedLines(alice, 2);
        skipJoinedLines(bob, 1);

        alice.send("MSG hello room");

        assertEquals("hello room", bob.expect(MessageType.CHAT).body());
        assertEquals("hello room", carol.expect(MessageType.CHAT).body());
        alice.expectNothing(400);
    }

    @Test
    @DisplayName("PM is only legal once you have joined")
    void pmBeforeHelloIsRejected() throws Exception {
        RawClient stranger = new RawClient(server.getPort());
        clients.add(stranger);

        stranger.send("PM alice hello");

        assertEquals(ErrorCode.MALFORMED, stranger.expect(MessageType.ERROR).errorCode());
        stranger.expectClosed();
    }
}
