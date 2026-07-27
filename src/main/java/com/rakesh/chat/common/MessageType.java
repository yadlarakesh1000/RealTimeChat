package com.rakesh.chat.common;


public enum MessageType {

    // ----- client to server -----
    HELLO,
    MSG,
    PM,
    /** Phase 6: send a private message to whoever last whispered to you. */
    REPLY,
    LIST,
    QUIT,

    // ----- server to client -----
    WELCOME,
    ERROR,
    CHAT,
    /** Phase 6: a private message someone sent to you. */
    WHISPER,
    /** Phase 6: confirmation that your own private message was delivered. */
    SENT,
    JOINED,
    LEFT,
    USERS;


    public boolean fromClient() {
        return switch (this) {
            case HELLO, MSG, PM, REPLY, LIST, QUIT -> true;
            default -> false;
        };
    }

    public boolean fromServer() {
        return !fromClient();
    }
}
