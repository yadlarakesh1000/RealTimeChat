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
 * Who is online, keyed case-insensitively.
 *
 * <p>{@link ConcurrentHashMap} rather than a synchronized map: registration is a
 * check-then-act ({@code putIfAbsent}) that must be atomic without serialising every
 * broadcast behind the same lock. See LEARNING-LOG.md, Phase 3.
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
     * Fan out one line to everyone except {@code except}.
     *
     * <p>Serialised once, not once per recipient: the wire bytes are identical for all of
     * them, and {@code serialize()} is the only part of a broadcast that is not O(1).
     *
     * <p><b>Overflow calls {@link ClientHandler#kick}, not {@code cleanup()}.</b> Phase 3
     * called {@code cleanup()} here, which was a latent stall: {@code cleanup()} joins the
     * slow client's writer thread for up to a second and then broadcasts its {@code LEFT}
     * — <i>on this thread</i>, nested inside this loop. So disconnecting a slow consumer
     * blocked the very broadcast that the per-client outbox exists to keep unblocked, and
     * two slow clients in one pass could stall a healthy sender for two seconds. {@code
     * kick()} closes the socket and returns; the doomed client's own thread does the rest.
     */
    public void broadcast(Message message, ClientHandler except) {
        String wireLine = message.serialize();

        // ConcurrentHashMap's iterator is weakly consistent: it never throws
        // ConcurrentModificationException, and it may or may not reflect a registration
        // that happens mid-iteration. For a broadcast that is exactly the right guarantee
        // — a client who joins halfway through simply misses a message sent before it
        // arrived, which is what "before it arrived" means.
        for (ClientHandler client : clientsByNickname.values()) {
            if (client == except) {
                continue;
            }
            if (!client.sendSerialized(wireLine)) {
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
