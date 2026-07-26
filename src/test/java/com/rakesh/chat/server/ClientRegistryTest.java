package com.rakesh.chat.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ClientRegistryTest {

    private ChatServer server;
    private ClientRegistry registry;

    static class MockSocket extends Socket {
        private final InputStream in;
        private final OutputStream out;

        public MockSocket(String input) {
            this.in = new ByteArrayInputStream(input.getBytes());
            this.out = new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public synchronized void close() {}
    }

    static class PipedMockSocket extends Socket {
        private final java.io.PipedInputStream in;
        private final java.io.PipedOutputStream outToClient;
        private final ByteArrayOutputStream out;

        public PipedMockSocket() throws IOException {
            this.outToClient = new java.io.PipedOutputStream();
            this.in = new java.io.PipedInputStream(outToClient);
            this.out = new ByteArrayOutputStream();
        }

        public void writeInput(String data) throws IOException {
            outToClient.write(data.getBytes());
            outToClient.flush();
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public synchronized void close() {
            try {
                in.close();
                outToClient.close();
            } catch (IOException ignored) {}
        }
    }

    @BeforeEach
    public void setUp() throws IOException {

        server = new ChatServer(0);
        registry = server.getRegistry();
    }

    @AfterEach
    public void tearDown() {
        server.shutdown();
    }

    @Test
    public void testRegisterAndFind() throws IOException {
        MockSocket socket1 = new MockSocket("HELLO 1 Alice\n");
        ClientHandler handler1 = new ClientHandler(socket1, server);

  
        assertTrue(registry.register("Alice", handler1));


        MockSocket socket2 = new MockSocket("alice\n");
        ClientHandler handler2 = new ClientHandler(socket2, server);
        assertFalse(registry.register("alice", handler2));
        assertFalse(registry.register("ALICE", handler2));


        assertEquals(1, registry.size());


        Optional<ClientHandler> found = registry.find("alice");
        assertTrue(found.isPresent());
        assertSame(handler1, found.get());
    }

    @Test
    public void testConditionalUnregister() throws IOException {
        MockSocket socket1 = new MockSocket("Alice\n");
        ClientHandler handler1 = new ClientHandler(socket1, server);

        MockSocket socket2 = new MockSocket("Alice\n");
        ClientHandler handler2 = new ClientHandler(socket2, server);

        assertTrue(registry.register("Alice", handler1));

     
        registry.unregister("Alice", handler2);
        assertEquals(1, registry.size());
        assertSame(handler1, registry.find("Alice").get());

        registry.unregister("Alice", handler1);
        assertEquals(0, registry.size());
        assertFalse(registry.find("Alice").isPresent());
    }

    @Test
    public void testOnlineNicknames() throws IOException {
        PipedMockSocket socket1 = new PipedMockSocket();
        ClientHandler handler1 = new ClientHandler(socket1, server);


        ExecutorService testPool = Executors.newSingleThreadExecutor();
        testPool.execute(handler1);

        // Phase 4: registration now goes through the protocol handshake, not a bare
        // nickname line.
        socket1.writeInput("HELLO 1 Alice\n");

      
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Collection<String> names = registry.onlineNicknames();
        assertTrue(names.contains("Alice"));
        assertEquals(1, names.size());

        // Clean up
        socket1.close();
        testPool.shutdownNow();
    }

    @Test
    public void testConcurrentRegistrationRace() throws InterruptedException {
        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        List<ClientHandler> handlers = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            try {
                handlers.add(new ClientHandler(new MockSocket("Bob\n"), server));
            } catch (IOException ignored) {}
        }

        for (int i = 0; i < threadCount; i++) {
            final ClientHandler h = handlers.get(i);
            pool.execute(() -> {
                try {
                    startLatch.await();
                    if (registry.register("Bob", h)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

     
        startLatch.countDown();
        endLatch.await();
        pool.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(1, registry.size());
    }
}
