package com.rakesh.chat.common;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public final class BoundedLineReader {


    public static final int DEFAULT_MAX_BYTES = 4096;

    private final InputStream in;
    private final int maxBytes;

    /**
     * The bytes of the line we are in the middle of reading.
     *
     * <p><b>Phase 9 moved this out of {@link #readLine()} and made it a field.</b> Before,
     * a half-read line lived in a local variable, so if {@code read()} threw — and from
     * Phase 9 it throws a {@code SocketTimeoutException} every 30 seconds by design — those
     * bytes were thrown away. The reader then carried on from the middle of the line, and
     * the tail of "MSG hello there" arrived as a line of its own reading "o there".
     *
     * <p>As a field, the partial line survives the exception and the next call carries on
     * exactly where it stopped. Not thread-safe, and does not need to be: one connection's
     * reader is only ever touched by that connection's reader thread.
     */
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

    /** True once at least one byte of the current line has been read. */
    private boolean sawAnyByte = false;

    public BoundedLineReader(InputStream in, int maxBytes) {
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, was " + maxBytes);
        }
        // Buffer internally: read() a byte at a time straight off a socket is a syscall
        // per byte. The bound is on the logical line, not on this buffer.
        this.in = (in instanceof BufferedInputStream) ? in : new BufferedInputStream(in, 8192);
        this.maxBytes = maxBytes;
    }

    public BoundedLineReader(InputStream in) {
        this(in, DEFAULT_MAX_BYTES);
    }

 
    public String readLine() throws IOException, ProtocolException {
        int b;
        while ((b = in.read()) != -1) {
            sawAnyByte = true;

            if (b == '\n') {
                return takeLine();
            }


            if (buf.size() == maxBytes) {
                // The framing is lost either way and the caller disconnects, so throw the
                // half line away rather than letting it turn up at the front of the next one.
                reset();
                throw new ProtocolException(ErrorCode.TOO_LONG,
                        "line exceeds " + maxBytes + " bytes");
            }
            buf.write(b);
        }

        if (!sawAnyByte) {
            return null;
        }
        return takeLine();
    }

    /** Decodes what we have, then clears it so the next call starts a fresh line. */
    private String takeLine() {
        String line = decode(buf);
        reset();
        return line;
    }

    private void reset() {
        buf.reset();
        sawAnyByte = false;
    }

    private static String decode(ByteArrayOutputStream buf) {
        byte[] bytes = buf.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--; 
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
