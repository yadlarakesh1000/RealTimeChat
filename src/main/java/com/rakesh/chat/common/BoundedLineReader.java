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
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 256));
        boolean sawAnyByte = false;

        int b;
        while ((b = in.read()) != -1) {
            sawAnyByte = true;

            if (b == '\n') {
                return decode(buf);
            }

            
            if (buf.size() == maxBytes) {
                throw new ProtocolException(ErrorCode.TOO_LONG,
                        "line exceeds " + maxBytes + " bytes");
            }
            buf.write(b);
        }

        if (!sawAnyByte) {
            return null; 
        }
        return decode(buf); 
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
