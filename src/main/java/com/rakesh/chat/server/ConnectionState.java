package com.rakesh.chat.server;

import com.rakesh.chat.common.MessageType;

/**
 * The lifecycle of one connection, modelled explicitly.
 *
 * <pre>
 *   CONNECTED ──HELLO accepted──▶ NAMED ──WELCOME + JOINED sent──▶ ACTIVE
 *       │                           │                                │
 *       └───────────────────────────┴────────────────────────────────┴──▶ CLOSING
 * </pre>
 *
 * <p>Before Phase 5 the server worked out the state by checking whether {@code nickname}
 * was null and which method it happened to be inside. Naming the states means the rule
 * "which commands are legal right now" lives in one place ({@link #permits(MessageType)})
 * instead of being spread over a pile of {@code if} statements.
 */
public enum ConnectionState {

    /** Socket accepted, streams open, nothing said yet. Only {@code HELLO} is legal. */
    CONNECTED,

    /**
     * The nickname is taken in the registry, but the room has not been told yet. The
     * handler is only in this state for a few microseconds. It is worth having, because
     * "owns a nickname" and "has been announced with JOINED" are different facts, and
     * cleanup needs the difference to decide whether to send {@code LEFT}.
     */
    NAMED,

    /** Fully joined. {@code MSG}, {@code PM}, {@code REPLY}, {@code LIST}, {@code QUIT} are legal. */
    ACTIVE,

    /** The connection is shutting down. No more commands are accepted. */
    CLOSING;

    /**
     * Is the client allowed to send this kind of message right now?
     *
     * <p>Server-to-client verbs (like {@code CHAT}) are never legal from a client in any
     * state, so that check lives here too — one method answers "is this line allowed".
     */
    public boolean permits(MessageType type) {
        if (type == null || !type.fromClient()) {
            return false;
        }
        return switch (this) {
            case CONNECTED -> type == MessageType.HELLO;
            // A second HELLO on an established connection is an error (PROTOCOL.md §4.2).
            case NAMED, ACTIVE -> type != MessageType.HELLO;
            case CLOSING -> false;
        };
    }

    /**
     * Is moving from this state to {@code next} allowed? The handler calls this before
     * changing state, so a mistake in our own code crashes a test instead of quietly
     * leaving a connection in a state the rest of the code does not expect.
     */
    public boolean canTransitionTo(ConnectionState next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case CONNECTED -> next == NAMED || next == CLOSING;
            case NAMED     -> next == ACTIVE || next == CLOSING;
            case ACTIVE    -> next == CLOSING;
            case CLOSING   -> false;
        };
    }
}
