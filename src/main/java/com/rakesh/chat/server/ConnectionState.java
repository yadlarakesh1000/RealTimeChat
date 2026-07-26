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
 * <p>Phase 4 inferred this from {@code nickname == null} and from <i>which method was on
 * the stack</i> — {@code handshake()} meant pre-login, {@code readLoop()} meant post.
 * That works right up until a third state is needed, at which point the state lives in
 * the control flow and cannot be queried, logged, or tested.
 *
 * <p>The value of naming the states is {@link #permits(MessageType)}: command legality
 * becomes a property of the state rather than a scatter of {@code if} statements in the
 * dispatcher. Adding a state in Phase 9 (AWAITING_PONG) means editing one enum.
 */
public enum ConnectionState {

    /** Socket accepted, streams open, nothing said yet. Only {@code HELLO} is legal. */
    CONNECTED,

    /**
     * The nickname is reserved in the registry but the join has not been announced.
     * Transient — the handler passes through it in a few microseconds. It exists so that
     * "holds a registry entry" and "has been announced to the room" are separable, which
     * is exactly the distinction {@code cleanup()} needs to decide whether to emit
     * {@code LEFT}.
     */
    NAMED,

    /** Fully joined. {@code MSG}, {@code PM}, {@code LIST}, {@code QUIT} are legal. */
    ACTIVE,

    /** Cleanup has begun. No further commands are accepted; the outbox is draining. */
    CLOSING;

    /**
     * Is {@code type} a legal thing for the <i>peer</i> to send while we are in this state?
     *
     * <p>Server-to-client verbs are never legal from a client, in any state — that check
     * belongs here rather than in the dispatcher so there is one answer to "is this line
     * allowed", not two.
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
     * Guards the transition table. Called by the setter so an illegal transition fails
     * loudly in a test rather than silently producing a connection in a state its own
     * code does not expect.
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
