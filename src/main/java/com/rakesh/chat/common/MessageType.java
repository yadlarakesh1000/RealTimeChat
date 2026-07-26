package com.rakesh.chat.common;


public enum MessageType {

    HELLO,
    MSG,
    PM,
    LIST,
    QUIT,

    WELCOME,
    ERROR,
    CHAT,
    WHISPER,
    JOINED,
    LEFT,
    USERS;


    public boolean fromClient() {
        return switch (this) {
            case HELLO, MSG, PM, LIST, QUIT -> true;
            default -> false;
        };
    }

    public boolean fromServer() {
        return !fromClient();
    }
}
