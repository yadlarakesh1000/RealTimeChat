package com.rakesh.chat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DoS fix. These tests are the difference between "I wrote a parser" and "I wrote a
 * parser that survives a hostile peer".
 */
class BoundedLineReaderTest {

    private static final int CAP = BoundedLineReader.DEFAULT_MAX_BYTES; // 4096

    private static BoundedLineReader reader(String data) {
        return reader(data, CAP);
    }

    private static BoundedLineReader reader(String data, int cap) {
        return new BoundedLineReader(
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), cap);
    }

    // ------------------------------------------------------------------
    // Ordinary framing
    // ------------------------------------------------------------------

    @Test
    void readsLinesInOrderThenNullAtEof() throws Exception {
        BoundedLineReader r = reader("one\ntwo\nthree\n");
        assertEquals("one", r.readLine());
        assertEquals("two", r.readLine());
        assertEquals("three", r.readLine());
        assertNull(r.readLine());
        assertNull(r.readLine(), "null must be sticky, not a one-shot");
    }

    @Test
    void emptyStreamIsImmediatelyNull() throws Exception {
        assertNull(reader("").readLine());
    }

    @Test
    void blankLineIsAnEmptyStringNotNull() throws Exception {
        BoundedLineReader r = reader("\n\n");
        assertEquals("", r.readLine());
        assertEquals("", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    void multiByteUtf8IsDecodedNotSplit() throws Exception {
        assertEquals("héllo 👋 wörld", reader("héllo 👋 wörld\n").readLine());
    }

    @Test
    @DisplayName("a multi-byte character split across internal reads is still decoded")
    void utf8SurvivesByteAtATimeReading() throws Exception {
        // A stream that hands over one byte per read() call, so every multi-byte
        // sequence is guaranteed to straddle a read boundary.
        byte[] data = "👋🏽é\n".getBytes(StandardCharsets.UTF_8);
        InputStream dripping = new InputStream() {
            private int i = 0;
            @Override public int read() {
                return (i < data.length) ? (data[i++] & 0xFF) : -1;
            }
        };
        assertEquals("👋🏽é", new BoundedLineReader(dripping, CAP).readLine());
    }

    // ------------------------------------------------------------------
    // The \r policy (PROTOCOL.md §1.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both \\r\\n and bare \\n terminate a line")
    void crlfAndLfBothTerminate() throws Exception {
        BoundedLineReader r = reader("telnet says this\r\nnc says this\n");
        assertEquals("telnet says this", r.readLine());
        assertEquals("nc says this", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    @DisplayName("exactly one trailing CR is stripped; a second is left for parse to reject")
    void onlyOneTrailingCrIsStripped() throws Exception {
        assertEquals("hi\r", reader("hi\r\r\n").readLine());
    }

    @Test
    @DisplayName("a CR in the middle does not terminate and is not stripped")
    void embeddedCrIsNotATerminator() throws Exception {
        BoundedLineReader r = reader("a\rb\n");
        assertEquals("a\rb", r.readLine());
        assertNull(r.readLine());
        // ...and parse is what rejects it, keeping framing and validation separate.
        assertThrows(ProtocolException.class, () -> Message.parse("MSG a\rb"));
    }

    @Test
    void loneCrWithoutLfDoesNotTerminate() throws Exception {
        BoundedLineReader r = reader("no newline here\r");
        assertEquals("no newline here", r.readLine(), "EOF returns the partial line");
        assertNull(r.readLine());
    }

    // ------------------------------------------------------------------
    // EOF mid-line (documented behaviour)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EOF mid-line returns the partial line, then null — as BufferedReader does")
    void eofMidLineReturnsThePartialLine() throws Exception {
        BoundedLineReader r = reader("complete\nincomplete");
        assertEquals("complete", r.readLine());
        assertEquals("incomplete", r.readLine());
        assertNull(r.readLine());
    }

    // ------------------------------------------------------------------
    // The cap — off-by-one boundary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line of exactly maxBytes is accepted")
    void exactlyAtTheCapIsAccepted() throws Exception {
        String line = "a".repeat(CAP);
        assertEquals(line, reader(line + "\n").readLine());
    }

    @Test
    @DisplayName("maxBytes + 1 is TOO_LONG")
    void oneByteOverTheCapIsRejected() {
        String line = "a".repeat(CAP + 1);
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> reader(line + "\n").readLine());
        assertEquals(ErrorCode.TOO_LONG, e.code());
    }

    @Test
    @DisplayName("the cap counts BYTES, not characters")
    void capIsMeasuredInBytesBeforeDecoding() {
        // 2049 two-byte characters = 4098 bytes, but only 2049 chars. A character-based
        // cap would wave this through.
        String line = "é".repeat(2049);
        assertEquals(4098, line.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(ProtocolException.class, () -> reader(line + "\n").readLine());
    }

    @Test
    void terminatorItselfDoesNotCountAgainstTheCap() throws Exception {
        // "aaa\r\n" with a cap of 4: the CR is part of the counted payload, the LF is not.
        assertEquals("aaa", reader("aaa\r\n", 4).readLine());
    }

    // ------------------------------------------------------------------
    // The actual attack
    // ------------------------------------------------------------------

    @Test
    @DisplayName("endless data with no terminator: TOO_LONG, bounded memory, no OOM")
    void infiniteStreamWithNoNewlineIsBounded() {
        // An infinite stream of 'a'. BufferedReader.readLine() would buffer this until
        // the heap died. We must fail after ~CAP bytes.
        InputStream endless = new InputStream() {
            long produced = 0;
            @Override public int read() {
                if (produced > 100L * 1024 * 1024) {
                    fail("reader consumed 100 MB without giving up — the cap is not working");
                }
                produced++;
                return 'a';
            }
        };

        BoundedLineReader r = new BoundedLineReader(endless, CAP);
        ProtocolException e = assertThrows(ProtocolException.class, r::readLine);
        assertEquals(ErrorCode.TOO_LONG, e.code());
        assertTrue(e.getMessage().contains(String.valueOf(CAP)), e.getMessage());
    }

    @Test
    @DisplayName("over-length does not resynchronise: framing is lost, caller must disconnect")
    void overLengthThrowsImmediatelyWithoutDraining() throws Exception {
        // The remainder of the over-long line is deliberately NOT skipped. We document
        // that the caller disconnects, so the leftovers are never parsed. This test
        // pins the behaviour so nobody "helpfully" adds resynchronisation later.
        //
        // Cap 4, payload "aaaaa": four bytes are buffered, the fifth trips the cap, and
        // the reader stops right there — leaving "\nok\n" unread. ("ok" is deliberately
        // short enough to fit under the cap itself.)
        BoundedLineReader r = reader("aaaaa\nok\n", 4);
        assertThrows(ProtocolException.class, r::readLine);

        assertEquals("", r.readLine(),
                "reader must resume exactly where it stopped, not at the next newline");
        assertEquals("ok", r.readLine());
    }

    // ------------------------------------------------------------------
    // Phase 9: surviving a read timeout mid-line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a timeout in the middle of a line does not lose the bytes already read")
    void aPartialLineSurvivesAnInterruptedRead() throws Exception {
        // Phase 9 gives the socket a 30-second read timeout, so read() now throws on a
        // perfectly healthy connection whenever nobody is typing. If a half-read line were
        // thrown away when that happened, "MSG hello there" split across the timeout would
        // arrive as a line reading "o there" — a corrupted message out of thin air.
        InputStream stalling = new InputStream() {
            private final byte[] data = "MSG hello there\n".getBytes(StandardCharsets.UTF_8);
            private int i = 0;
            private boolean stalled = false;

            @Override public int read() throws IOException {
                if (i == 5 && !stalled) {       // right in the middle of the line
                    stalled = true;
                    throw new SocketTimeoutException("Read timed out");
                }
                return (i < data.length) ? (data[i++] & 0xFF) : -1;
            }

            // A real socket overrides this, and a timeout comes straight out of it. The
            // default InputStream version quietly SWALLOWS an IOException once it has read
            // at least one byte — so without this override the timeout would never reach
            // the reader and the test would be proving nothing. (See LEARNING-LOG.md, B9.)
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int c = read();
                if (c == -1) {
                    return -1;
                }
                b[off] = (byte) c;
                return 1;
            }
        };

        BoundedLineReader r = new BoundedLineReader(stalling, CAP);
        assertThrows(SocketTimeoutException.class, r::readLine);
        assertEquals("MSG hello there", r.readLine(),
                "the reader must carry on from where the timeout interrupted it");
    }

    @Test
    @DisplayName("a completed line is not repeated on the next call")
    void theBufferIsClearedAfterEveryLine() throws Exception {
        // The other half of the same change: the buffer is now a field, so forgetting to
        // clear it would make every line drag the previous one along in front of it.
        BoundedLineReader r = reader("first\nsecond\n");
        assertEquals("first", r.readLine());
        assertEquals("second", r.readLine());
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void rejectsNonsenseConstructorArguments() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> new BoundedLineReader(null, CAP));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLineReader(in, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLineReader(in, -1));
    }

    @Test
    void ioExceptionsPropagateRatherThanBecomingProtocolErrors() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("socket reset");
            }
        };
        assertThrows(IOException.class, () -> new BoundedLineReader(broken, CAP).readLine());
    }
}
