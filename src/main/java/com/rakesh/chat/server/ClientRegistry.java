package com.rakesh.chat.server;

import com.rakesh.chat.common.Message;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The list of who is online, looked up by nickname (ignoring upper/lower case).
 *
 * <p>A {@link ConcurrentHashMap} rather than a normal map with {@code synchronized} around
 * it, because {@code putIfAbsent} lets us claim a nickname in one atomic step without
 * making every broadcast queue up behind the same lock. See LEARNING-LOG.md, Phase 3.
 *
 * <p>{@link #find(String)} is what makes Phase 6 private messaging possible: it turns a
 * nickname typed by one user into the handler object for another user's socket.
 */
public class ClientRegistry {

    /** Key is the lower-cased nickname; the handler remembers the original spelling. */
    private final Map<String, ClientHandler> clientsByNickname = new ConcurrentHashMap<>();

    public boolean register(String nickname, ClientHandler h) {
        String key = nickname.toLowerCase(Locale.ROOT);
        return clientsByNickname.putIfAbsent(key, h) == null;
    }

    /**
     * Conditional removal: only unregisters if {@code h} is still the handler holding
     * the name, so a late cleanup cannot evict whoever took the nickname afterwards.
     */
    public void unregister(String nickname, ClientHandler h) {
        String key = nickname.toLowerCase(Locale.ROOT);
        clientsByNickname.remove(key, h);
    }

    public Optional<ClientHandler> find(String nickname) {
        String key = nickname.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(clientsByNickname.get(key));
    }

 
    public Collection<String> onlineNicknames() {
        return clientsByNickname.values().stream()
                .map(ClientHandler::getNickname)
                .filter(name -> name != null)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toUnmodifiableList());
    }


    /**
     * Send one line to everyone except {@code except}.
     *
     * <p>Private messages never come through here — see
     * {@code ClientHandler.sendPrivateMessage}. That is the privacy boundary for Phase 6:
     * if a whisper cannot reach this method, it cannot leak to the room.
     *
     * <p><b>Phase 8 changed this method.</b> It used to call {@code message.serialize()}
     * once and hand the same text to everybody, which was cheaper. Now it calls each
     * client's own {@code send()}, because that is where encryption happens — pre-building
     * the line here would have quietly shipped the room's messages in clear text. The cost
     * is one encryption per recipient instead of one per message; the benefit is that
     * "everything out goes through {@code ClientHandler.send}" stays true with no
     * exceptions, and each recipient's copy gets its own IV.
     *
     * <p>If a client's outbox is full we {@link ClientHandler#kick} them rather than
     * calling {@code cleanup()}. {@code cleanup()} waits up to a second for that client's
     * writer thread, and it would do that waiting <i>on this thread</i>, in the middle of
     * this loop — so removing one slow client would stall the broadcast for everyone else.
     */
    public void broadcast(Message message, ClientHandler except) {
        // ConcurrentHashMap's iterator never throws ConcurrentModificationException, and it
        // may or may not include someone who joins while we are looping. That is fine: a
        // client who joins halfway through just misses a message sent before they arrived.
        for (ClientHandler client : clientsByNickname.values()) {
            if (client == except) {
                continue;
            }
            if (!client.send(message)) {
                ServerLog.warn("OUTBOX_FULL", client.getNickname(),
                        "disconnecting slow consumer");
                client.kick("outbox overflow");
            }
        }
    }

    public int size() {
        return clientsByNickname.size();
    }
    public Collection<ClientHandler> all() {
        return Collections.unmodifiableCollection(clientsByNickname.values());
    }
}
