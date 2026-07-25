package com.rakesh.chat.server;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClientRegistry {
    private final Map<String, ClientHandler> clientsByNickname = new ConcurrentHashMap<>();

    /**
     * Atomically registers a client.
     * Keyed on nickname.toLowerCase(Locale.ROOT) to prevent duplicate names in different cases.
     * Returns true if registration succeeded, false if nickname is already in use.
     */
    public boolean register(String nickname, ClientHandler h) {
        String key = nickname.toLowerCase(Locale.ROOT);
        return clientsByNickname.putIfAbsent(key, h) == null;
    }

    /**
     * Unregisters a client.
     * Uses conditional removal to avoid evicting a reconnected namesake.
     */
    public void unregister(String nickname, ClientHandler h) {
        String key = nickname.toLowerCase(Locale.ROOT);
        clientsByNickname.remove(key, h);
    }

    /**
     * Finds a client handler by nickname (case-insensitive).
     */
    public Optional<ClientHandler> find(String nickname) {
        String key = nickname.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(clientsByNickname.get(key));
    }

    /**
     * Returns an unmodifiable copy of display nicknames (not the lowercase keys).
     * This avoids letting callers mutate the registry and prevents concurrent modification issues.
     */
    public Collection<String> onlineNicknames() {
        return clientsByNickname.values().stream()
                .map(ClientHandler::getNickname)
                .filter(name -> name != null)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Broadcasts a message to all registered clients except the specified handler.
     * Acquires no locks and uses non-blocking send().
     */
    public void broadcast(String message, ClientHandler except) {
        for (ClientHandler client : clientsByNickname.values()) {
            if (client != except) {
                boolean queued = client.send(message);
                if (!queued) {
                    System.err.println("Outbox full for " + client.getNickname() + ". Disconnecting client.");
                    client.cleanup();
                }
            }
        }
    }

    /**
     * Returns the size of the registry.
     */
    public int size() {
        return clientsByNickname.size();
    }

    /**
     * Returns an unmodifiable collection of all handlers (useful for shutdown).
     */
    public Collection<ClientHandler> all() {
        return Collections.unmodifiableCollection(clientsByNickname.values());
    }
}
