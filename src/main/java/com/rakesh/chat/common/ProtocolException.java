package com.rakesh.chat.common;


public class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ProtocolException(ErrorCode code, String message) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
