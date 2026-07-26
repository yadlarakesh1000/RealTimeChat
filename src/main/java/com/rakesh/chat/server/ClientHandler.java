package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;
import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;


public class ClientHandler implements Runnable {

    private static final String SERVER_NAME = "rakesh-chat";

    private static final String POISON = new String("__POISON__");

    private final Socket socket;
    private final ChatServer server;
    private final ClientRegistry registry;

    private final BoundedLineReader in;
    private final PrintWriter out;

    private final BlockingQueue<String> outbox;
    private final Thread writerThread;

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    private volatile String nickname;

    /** True once JOINED has been broadcast, so cleanup knows whether to broadcast LEFT. */
    private volatile boolean joined = false;

    public ClientHandler(Socket socket, ChatServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.registry = server.getRegistry();

       
        this.in = new BoundedLineReader(socket.getInputStream(),
                BoundedLineReader.DEFAULT_MAX_BYTES);

        this.out = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8)),
                true); // autoFlush on println()

        this.outbox = new LinkedBlockingQueue<>(256);
        this.writerThread = new Thread(this::writerLoop, "writer-pending");
    }



    @Override
    public void run() {
        try {
            
            writerThread.start();

            if (!handshake()) {
                return; 
            }

            readLoop();

        } catch (IOException e) {
            
            System.out.println("[DISCONNECT] " + who() + ": " + e.getMessage());
        } catch (Throwable t) {
          
            System.err.println("Bug in ClientHandler for " + who() + ": " + t);
            t.printStackTrace();
        } finally {
            cleanup();
        }
    }


    private boolean handshake() throws IOException {
      
        String line;
        try {
            line = in.readLine();
        } catch (ProtocolException e) {
            send(Message.error(e.code(), e.getMessage()));
            return false;
        }
        if (line == null) {
            return false; // connected and closed without saying anything
        }

        Message hello;
        try {
            hello = Message.parse(line);
        } catch (ProtocolException e) {
            send(Message.error(e.code(), e.getMessage()));
            return false;
        }

        if (hello.type() != MessageType.HELLO) {
            send(Message.error(ErrorCode.MALFORMED,
                    "expected HELLO as the first message, got " + hello.type()));
            return false;
        }

        String requested = hello.sender();
        if (!registry.register(requested, this)) {
            send(Message.error(ErrorCode.NICK_TAKEN, "nickname already in use: " + requested));
            return false;
        }

        // Registered: from here on cleanup() must unregister and announce the departure.
        this.nickname = requested;
        this.joined = true;

        Thread.currentThread().setName("reader-" + requested);
        writerThread.setName("writer-" + requested);

        send(Message.welcome(requested, SERVER_NAME));
        registry.broadcast(Message.joined(requested), this);
        System.out.println("[JOINED] " + requested + " (" + registry.size() + " online)");
        return true;
    }

    private void readLoop() throws IOException {
        while (true) {
            String line;
            try {
                line = in.readLine();
            } catch (ProtocolException e) {
                // Only TOO_LONG can reach here. Framing sync is gone, so there is nothing
                // safe to resume from: report and disconnect.
                send(Message.error(e.code(), e.getMessage()));
                System.out.println("[TOO_LONG] " + who() + " — disconnecting");
                return;
            }

            if (line == null) {
                return; // clean EOF
            }

            try {
                if (!dispatch(Message.parse(line))) {
                    return; // QUIT
                }
            } catch (ProtocolException e) {
                // A bad message kills the message, not the connection.
                send(Message.error(e.code(), e.getMessage()));
            }
        }
    }

  
    private boolean dispatch(Message m) throws ProtocolException {
        if (!m.type().fromClient()) {
            // WELCOME, CHAT, USERS... are server-to-client verbs. A client sending one is
            // malformed input, not a valid message. parse() is direction-agnostic on
            // purpose; this is where direction gets enforced.
            throw new ProtocolException(ErrorCode.MALFORMED,
                    m.type() + " is a server-to-client verb");
        }

        switch (m.type()) {
            case MSG -> registry.broadcast(Message.chat(nickname, m.body()), this);

            case LIST -> send(Message.users(registry.onlineNicknames()));

            case QUIT -> {
                System.out.println("[QUIT] " + nickname);
                return false;
            }

            case PM -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "PM is not implemented yet (Phase 6)");

            case HELLO -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "already registered as " + nickname);

            default -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "unhandled verb: " + m.type());
        }
        return true;
    }

  
    public boolean send(Message message) {
        return sendSerialized(message.serialize());
    }

    
    boolean sendSerialized(String wireLine) {
        return outbox.offer(wireLine);
    }

    

    private void writerLoop() {
        try {
            while (true) {
                String line = outbox.take();
                if (line == POISON) { // identity, not equality
                    break;
                }
                out.println(line);
                if (out.checkError()) {
                    System.out.println("[WRITE-FAIL] " + who());
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    public void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        String nick = nickname;

        
        if (nick != null) {
            registry.unregister(nick, this);
        }

        if (joined && nick != null) {
            registry.broadcast(Message.left(nick), this);
            System.out.println("[LEFT] " + nick + " (" + registry.size() + " online)");
        }


        if (!outbox.offer(POISON)) {
           
            writerThread.interrupt();
        }


        if (Thread.currentThread() != writerThread) {
            try {
                writerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writerThread.interrupt(); // no-op if it already exited
        }

        try {
            socket.close();
        } catch (IOException ignored) {
           
        }

        // 6. Release the connection slot.
        server.decrementActiveConnections();
    }

    public String getNickname() {
        return nickname;
    }

    private String who() {
        String nick = nickname;
        return (nick != null) ? nick : String.valueOf(socket.getRemoteSocketAddress());
    }
}
