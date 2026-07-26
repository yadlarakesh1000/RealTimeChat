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


    public void broadcast(Message message, ClientHandler except) {
        String wireLine = message.serialize();

        for (ClientHandler client : clientsByNickname.values()) {
            if (client == except) {
                continue;
            }
            if (!client.sendSerialized(wireLine)) {
                System.err.println("Outbox full for " + client.getNickname()
                        + "; disconnecting slow consumer.");
                client.cleanup();
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
