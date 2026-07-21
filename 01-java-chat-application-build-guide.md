# Project 1 — Java Real-Time Chat Application
## A build-it-yourself guide (no AI writing your code)

**Stack:** Java 17+, `java.net` sockets, threads, `java.util.concurrent`, JavaFX, `javax.crypto`
**Estimated time:** 25–40 focused hours across 10 phases
**End state:** A server that handles many simultaneous clients, a JavaFX desktop client, group + private messaging, nicknames, connection logs, and encrypted message payloads.

---

## How to use this guide

Each phase has five parts:

| Part | What it does |
|---|---|
| **Concepts first** | Read/learn these *before* writing code. If you skip this you'll copy-paste blindly. |
| **Self-check** | Answer out loud. If you can't, re-read the concepts. |
| **Build tasks** | What to implement. You get class names, method signatures, and constraints — never full bodies. |
| **Checkpoint** | A concrete, testable "it works" definition. Don't move on until it passes. |
| **When it breaks** | Real errors you'll hit, what they actually mean, and *prompts to ask an AI that produce understanding instead of code.* |

### The rule for using AI on this project

You are allowed to ask AI to **explain, diagnose, and review**. You are not allowed to ask it to **write**.

Banned prompt shapes:
- "Write a multithreaded chat server in Java"
- "Fix this class" (pasting the whole file and taking the output)
- "Give me the code for X"

Allowed prompt shapes:
- "Explain what a `ServerSocket` backlog queue is and what happens when it fills"
- "Here is my stack trace and the 6 lines around it. What category of bug produces this? Don't give me code."
- "I chose `ConcurrentHashMap` for the client registry. What are three failure modes of that choice?"

Keep a file called `LEARNING-LOG.md` in your repo. Every time you get stuck for more than 20 minutes, log: the symptom, your hypothesis, what was actually wrong, and the concept you were missing. This file is your interview ammunition later.

---

## Phase 0 — Environment and mental model

### Concepts first

1. **What TCP actually gives you.** A byte stream. Not messages. Not lines. Not objects. A stream of bytes with ordering and delivery guarantees. Every "message boundary" you think exists is one *you* invented.
2. **The socket lifecycle.** A `ServerSocket` binds to a port and listens. `accept()` blocks until a client connects, then returns a *new* `Socket` dedicated to that client. The `ServerSocket` keeps listening. Two different objects, two different jobs.
3. **Blocking I/O.** `accept()`, `read()`, and `readLine()` all block — the calling thread parks until something happens. This single fact is why you need threads.
4. **Ports, localhost, 127.0.0.1.** Know what a port is, why ports below 1024 need privileges, and why `localhost` works for testing but won't let your friend connect.

Read: the Javadoc for `java.net.ServerSocket` and `java.net.Socket`. All of it. It's short.

### Self-check
- If a client sends `"hello"` then `"world"` in two fast writes, is the server guaranteed to `read()` them as two separate reads? Why not?
- What does `accept()` return, and how is it different from the object you called it on?
- What happens to the second client if your server is single-threaded and the first client never disconnects?

### Build tasks
- Create a Maven or Gradle project. Package structure:
  ```
  com.yourname.chat
    ├── server
    ├── client
    ├── common      (protocol, crypto, shared models)
    └── Main.java   (per module)
  ```
- Set up Git. First commit before you write any logic.
- Configure JavaFX later (Phase 7) — do not do it now. Adding JavaFX to the build early is a classic way to waste a day on module-path errors before you've written a line of networking.

### Checkpoint
`mvn compile` or `gradle build` succeeds on an empty project. Git repo initialized with a `.gitignore` that excludes `target/`, `build/`, `.idea/`.

### When it breaks
**Prompt:** "Explain the difference between the Maven `compiler.source`/`compiler.target` properties and the `maven.compiler.release` property, and why using the wrong one causes runtime errors that compile fine."

---

## Phase 1 — Single-client echo server

Goal: prove you understand sockets before adding concurrency.

### Concepts first

1. **Streams vs Readers.** `socket.getInputStream()` gives bytes. `InputStreamReader` adapts bytes → chars using a charset. `BufferedReader` adds buffering and `readLine()`. Understand each layer's job. **Always specify `StandardCharsets.UTF_8` explicitly** — platform default charset is a bug waiting for a different machine.
2. **Flushing.** `PrintWriter` and `BufferedWriter` buffer output. If you don't flush, your data sits in memory and the other side hangs forever waiting. `new PrintWriter(stream, true)` enables autoflush on `println()` — know that it does *not* autoflush on `print()` or `write()`.
3. **try-with-resources.** Sockets are `Closeable`. Closing a socket closes its streams. Closing a stream closes the socket. Know the ordering implications.
4. **Detecting disconnect.** `readLine()` returns `null` at end-of-stream. It does *not* throw. A `null` return is how you learn the peer hung up gracefully. An `IOException` is how you learn they hung up rudely.

### Self-check
- Why is `readLine()` returning `null` different from it returning `""`?
- If you never call `flush()`, where physically is your data?
- Which side closes the socket first, and does it matter?

### Build tasks

**`server/EchoServer.java`**
```
public class EchoServer {
    private final int port;
    public void start() throws IOException   // bind, accept ONE client, echo lines until null
}
```
- Bind on a configurable port (default 5000).
- Accept exactly one connection.
- Loop: read a line, print it to console with a timestamp, write it back prefixed with `ECHO: `.
- Exit cleanly when the client disconnects.

**Test with `telnet localhost 5000` or `nc localhost 5000`** — do not write a Java client yet. You want to prove the server works independent of your client bugs.

### Checkpoint
You can `nc localhost 5000`, type `hello`, and see `ECHO: hello`. You press Ctrl+C and the server prints a clean "client disconnected" message and exits without a stack trace.

### When it breaks

**Symptom: `java.net.BindException: Address already in use`**
Your previous run is still holding the port, or you ran two instances. Find it: `lsof -i :5000` (Mac/Linux) or `netstat -ano | findstr :5000` (Windows).
**Prompt:** "Explain the TCP TIME_WAIT state and why a server port can stay unavailable for a minute or two after the process exits. What does `setReuseAddress(true)` change about this, and what's the risk?"

**Symptom: client connects but nothing echoes back; both sides hang**
You didn't flush.
**Prompt:** "I'm writing to a socket with PrintWriter and the peer never receives it, but I see no exception. Walk me through every buffering layer between my `println` call and the network card, and where data can get stuck at each one. No code."

**Symptom: `SocketException: Connection reset`**
Peer closed abruptly while you were reading or writing.
**Prompt:** "What's the difference between a TCP FIN and a TCP RST, and which application-level behaviours produce each? Which Java exception maps to which?"

---

## Phase 2 — Multi-client with threads

### Concepts first

1. **Thread-per-connection model.** The classic design: main thread loops on `accept()`, hands each new `Socket` to a dedicated thread. Understand its cost — each thread eats ~1MB of stack. 10,000 clients is not viable. 200 is fine. Know *why* NIO/virtual threads exist as the answer, even though you're not using them.
2. **`Runnable` vs `Thread` vs `ExecutorService`.** You will use an `ExecutorService`. Understand `newFixedThreadPool` vs `newCachedThreadPool` and which is safer against a connection-flood.
3. **Daemon vs user threads.** Which kind prevents the JVM from exiting?
4. **Java 21 virtual threads.** If you're on 21+, `Executors.newVirtualThreadPerTaskExecutor()` makes thread-per-connection scale. Read about it — this is a strong interview talking point even if you use platform threads.

### Self-check
- What is the maximum number of concurrent clients your design supports, and what happens to client N+1?
- If a `ClientHandler` throws an uncaught exception, what happens to the server? To the other clients?
- Why is `newCachedThreadPool` dangerous for a public-facing server?

### Build tasks

**`server/ChatServer.java`**
```
public class ChatServer {
    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    public void start()                       // accept loop, submit ClientHandler to pool
    public void shutdown()                    // close serverSocket, shutdown pool gracefully
}
```

**`server/ClientHandler.java` implements `Runnable`**
```
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ChatServer server;          // or a Room/Registry reference
    private BufferedReader in;
    private PrintWriter out;

    public void run()                         // setup streams, read loop, cleanup in finally
    public void send(String message)          // called by OTHER threads — think about this
    private void cleanup()                    // idempotent: close socket, deregister self
}
```

**Critical design point:** `send()` is called by *other* threads (when someone else broadcasts). `run()`'s read loop is on *this* thread. That means your `PrintWriter` is touched by multiple threads. Decide now how you'll handle that — synchronize on the writer, or give each handler an outbound queue with its own writer thread. Write your reasoning in `LEARNING-LOG.md`.

Wrap the *entire* body of `run()` in try/catch/finally. An unhandled exception in one handler must never take down the server.

### Checkpoint
Open three `nc` sessions. All three connect. Kill one with Ctrl+C. The server logs the disconnect, the other two are unaffected, and the server keeps accepting new connections.

### When it breaks

**Symptom: server stops accepting after the first client**
Your `accept()` isn't in a loop, or you're doing the read loop on the accept thread.
**Prompt:** "Describe the correct control flow of an accept loop in a thread-per-connection TCP server. Which work belongs on the acceptor thread and which must be delegated? Explain the reasoning, don't write the loop."

**Symptom: `ConcurrentModificationException` when broadcasting**
You're iterating a plain `ArrayList` of clients while another thread adds/removes.
**Prompt:** "Explain fail-fast iterators and the modCount mechanism in `java.util` collections. Compare `CopyOnWriteArrayList`, `Collections.synchronizedList`, and `ConcurrentHashMap.newKeySet()` for a registry of active connections that is read constantly and written rarely."

**Symptom: shutdown hangs**
`accept()` is blocking and won't return. Closing the `ServerSocket` from another thread makes `accept()` throw `SocketException` — that's the intended mechanism, but you have to catch it and distinguish "we're shutting down" from "real error".
**Prompt:** "How do you cleanly interrupt a thread blocked in `ServerSocket.accept()`? Explain why `Thread.interrupt()` alone doesn't work for blocking socket I/O."

---

## Phase 3 — Shared state and broadcast

### Concepts first

1. **The Java Memory Model, informally.** Without synchronization, thread A's write to a field may never become visible to thread B. Not "eventually" — possibly *never*. Understand `volatile` (visibility, no atomicity) vs `synchronized` (visibility + mutual exclusion) vs `AtomicX` (visibility + atomic ops on one variable).
2. **Race conditions vs visibility bugs.** Different problems, different fixes. Be able to give an example of each.
3. **Check-then-act.** `if (!map.containsKey(k)) map.put(k, v)` is a race even on a `ConcurrentHashMap`. `putIfAbsent` exists for exactly this. This will matter for nickname uniqueness in Phase 5.
4. **Lock ordering and deadlock.** If handler A holds its own lock and wants B's, while B holds its own and wants A's — you're dead. Design so broadcasting never requires holding two handler locks.

### Self-check
- Give a concrete two-thread interleaving that breaks `count++`.
- Why doesn't `volatile` fix `count++`?
- Your broadcast iterates all clients and calls `send()` on each. If one client's TCP buffer is full and its `send()` blocks, what happens to the broadcast? To everyone else's messages? (This is the **slow consumer problem** — the single most interesting design issue in this project.)

### Build tasks

**`server/ClientRegistry.java`**
```
public class ClientRegistry {
    private final Map<String, ClientHandler> clientsByNickname;   // pick the impl deliberately
    public boolean register(String nickname, ClientHandler h)     // atomic, returns false if taken
    public void unregister(String nickname)
    public Optional<ClientHandler> find(String nickname)
    public Collection<String> onlineNicknames()
    public void broadcast(String message, ClientHandler except)
}
```

**Solve the slow-consumer problem.** Recommended approach: give each `ClientHandler` a `BlockingQueue<String>` outbox and a dedicated writer thread that drains it. `send()` becomes `outbox.offer(msg)` — non-blocking, never stalls the broadcaster. Use a *bounded* queue and decide the policy when it's full (drop the message, or disconnect the slow client). Document the choice.

### Checkpoint
Four clients connected. One is deliberately stalled (connect with `nc` and don't read — you can simulate by suspending the process with Ctrl+Z). The other three still exchange messages at full speed. The stalled client is either dropped or its messages queue up, per your documented policy — but it never freezes anyone else.

### When it breaks

**Symptom: nickname collisions slip through under fast reconnects**
Check-then-act race.
**Prompt:** "Explain why `containsKey` followed by `put` on a ConcurrentHashMap is not atomic, and what guarantee `putIfAbsent` provides at the memory-model level."

**Symptom: whole server freezes when one client's network is slow**
**Prompt:** "Explain the slow consumer problem in a broadcast messaging server. Compare three mitigation strategies — per-client bounded outbound queues, dropping messages, and disconnecting slow clients — including the failure mode each one introduces."

---

## Phase 4 — Protocol design

This is the phase most people skip, and it's the one that makes the rest easy.

### Concepts first

1. **You must define message framing.** TCP gives you a byte stream. Options: newline-delimited text (simplest — use this), length-prefixed binary, or a serialization format. Newline-delimited means **no message may contain a raw newline** — decide how you escape it.
2. **Wire format vs domain model.** The bytes on the wire are not your Java objects. Have an explicit encode/decode step. This becomes essential in Phase 8 when you encrypt.
3. **Forward compatibility.** What happens when a v1 client meets a v2 server? Version your protocol in the handshake.
4. **Never trust the peer.** Every field can be malformed, oversized, or hostile. Cap message length. Validate before you act.

### Build tasks

Write a file `PROTOCOL.md` in your repo **before writing code**. Specify:

```
Framing:   UTF-8 text, one message per line, '\n' terminated, max 4096 bytes/line
Direction: C→S = client to server, S→C = server to client

C→S  HELLO <protocolVersion> <nickname>
S→C  WELCOME <yourNickname> <serverName>
S→C  ERROR <code> <humanReadableMessage>

C→S  MSG <text>                    broadcast to room
C→S  PM <targetNickname> <text>    private message
C→S  LIST
C→S  QUIT

S→C  CHAT <fromNickname> <timestamp> <text>
S→C  WHISPER <fromNickname> <timestamp> <text>
S→C  JOINED <nickname>
S→C  LEFT <nickname> <reason>
S→C  USERS <comma,separated,nicknames>
```

Error codes: define at least `NICK_TAKEN`, `BAD_VERSION`, `MALFORMED`, `NO_SUCH_USER`, `TOO_LONG`, `RATE_LIMITED`.

**`common/Message.java`**
```
public record Message(MessageType type, String sender, String target, String body, Instant timestamp) {
    public static Message parse(String line) throws ProtocolException
    public String serialize()
}
```

Parsing rules to decide and document: how many splits, what happens to extra spaces in the body, how you escape a literal newline, what you do with an unknown verb (ignore vs error).

### Checkpoint
You can `nc` into your server, type raw protocol lines by hand, and drive every feature. Malformed input produces an `ERROR` line, never a stack trace or a disconnect-without-explanation.

### When it breaks

**Symptom: messages arrive concatenated or split**
You assumed one `read()` = one message.
**Prompt:** "Explain TCP message framing and why application protocols need explicit delimiters or length prefixes. Give three real-world examples of each approach."

**Symptom: `split()` mangles messages containing spaces**
**Prompt:** "Explain the limit parameter of `String.split(regex, limit)` and how negative, zero, and positive values differ. Why does this matter for parsing a text protocol where the last field is free-form?"

---

## Phase 5 — Nicknames, joins, leaves, logs

### Concepts first
- **Handshake state machine.** A connection is in state `CONNECTED → NAMED → ACTIVE → CLOSING`. Commands are only legal in certain states. Model this explicitly (an enum field on the handler), don't infer it from nulls.
- **Idle timeouts.** `socket.setSoTimeout(ms)` makes `read()` throw `SocketTimeoutException` instead of blocking forever. Use it to reap dead connections and to enforce a handshake deadline.
- **Structured logging.** Log with a timestamp, thread name, client address, and event type. Consider SLF4J + Logback rather than `System.out`.

### Build tasks
- Enforce the handshake: no `HELLO` within 10 seconds → disconnect with `ERROR BAD_VERSION` or a timeout code.
- Nickname validation: length 3–16, `[A-Za-z0-9_]` only, case-insensitive uniqueness. Reject with `ERROR NICK_TAKEN`.
- Broadcast `JOINED`/`LEFT` events to everyone else.
- Implement `LIST` → `USERS`.
- **Connection log**: append to `logs/connections.log` — `timestamp | event | nickname | remoteAddress | durationSeconds`. Events: `CONNECT`, `HANDSHAKE_OK`, `HANDSHAKE_FAIL`, `DISCONNECT`, `KICKED`.
- Add a simple rate limit: max N messages per 10 seconds per client, then `ERROR RATE_LIMITED`. A token bucket is the clean way; explain it in your log file.

### Checkpoint
Two clients try to take the same nickname simultaneously (script it with two `nc` pipes) — exactly one succeeds. The connection log shows a complete, correct record of a session from connect to disconnect including duration.

### When it breaks
**Prompt:** "Explain the token bucket and leaky bucket rate limiting algorithms, their differences in burst handling, and which is more appropriate for a chat server. No code."
**Prompt:** "Explain `Socket.setSoTimeout`. Does it apply to connect, read, write, or all three? What is the separate mechanism for each?"

---

## Phase 6 — Private messaging

### Concepts first
- **Routing.** Server-side lookup of target → handler. What if the target disconnects between lookup and send? (Answer: the send fails, or the handler is already deregistered. Handle both.)
- **Privacy boundary.** A private message must never appear in the broadcast path. Audit your code for this.
- **Self-messaging and echo.** Should the sender see their own PM? Decide, document, be consistent.

### Build tasks
- `PM <target> <text>` → `WHISPER` to target, plus a confirmation to sender.
- `ERROR NO_SUCH_USER` if the target is offline.
- Optional but valuable: `/reply` semantics — server tracks each client's last PM sender.

### Checkpoint
Three clients A, B, C. A PMs B. C sees nothing — verify by inspecting C's raw stream, not the GUI.

### When it breaks
**Prompt:** "In a concurrent map-based routing table, describe the TOCTOU window between looking up a target and sending to it, and the two standard ways to make the send-or-fail decision safe."

---

## Phase 7 — JavaFX client GUI

Now, and only now, add JavaFX.

### Concepts first

1. **JavaFX is not in the JDK anymore** (since Java 11). You add it as a dependency (`org.openjfx:javafx-controls`) plus the `javafx-maven-plugin`, or you run with `--module-path` and `--add-modules`. Expect an hour of build friction. This is normal.
2. **The JavaFX Application Thread.** All UI mutation happens on it. Your socket reader thread must never touch a `TextArea` directly. Use `Platform.runLater(Runnable)`.
3. **Never block the FX thread.** If you call `socket.connect()` on it, the UI freezes. Networking goes on a background thread, always. Look at `javafx.concurrent.Task` and `Service`.
4. **Observable collections & bindings.** `ObservableList` + `ListView` gives you an auto-updating user list for free. Learn `ListView.setCellFactory` for custom rendering.
5. **Lifecycle.** `Application.start()`, `stop()`. Close your socket in `stop()` or your JVM won't exit.

### Self-check
- What happens if you call `textArea.appendText()` from a socket thread? (It may work sometimes. That's worse than always failing — explain why.)
- Why is `Platform.runLater` a queue and not a direct call?

### Build tasks

**`client/ChatClient.java`** — pure networking, zero UI imports.
```
public class ChatClient {
    public void connect(String host, int port, String nickname) throws IOException
    public void send(Message m)
    public void setListener(Consumer<Message> onMessage)   // called on the reader thread
    public void disconnect()
}
```
Keeping this UI-free is the point. It means you can unit-test it and swap the GUI later.

**`client/ui/ChatWindow.java`** — JavaFX layer.
Layout suggestion (`BorderPane`):
- Top: connection bar (host, port, nickname, Connect/Disconnect button, status indicator)
- Center: `ListView<Message>` of the conversation with a custom cell factory (own messages right-aligned, system messages in grey italic, whispers in a distinct colour)
- Right: `ListView<String>` of online users; double-click starts a PM
- Bottom: `TextField` + Send button; Enter sends

The bridge: `ChatClient`'s listener does `Platform.runLater(() -> observableMessages.add(m))`.

Style it with an external `chat.css`. Do not inline styles.

### Checkpoint
Two GUI clients on the same machine chat in real time. You can disconnect one from the UI and reconnect without restarting the app. Closing the window terminates the JVM completely (check with `jps` or Task Manager).

### When it breaks

**Symptom: `Error: JavaFX runtime components are missing`**
**Prompt:** "Explain why JavaFX was removed from the JDK in Java 11 and the three ways to run a JavaFX app today: module path with --add-modules, the javafx-maven-plugin, and a shaded/jlink image. Compare their trade-offs."

**Symptom: `IllegalStateException: Not on FX application thread`**
This is the *good* outcome — JavaFX caught you. Fix with `Platform.runLater`.
**Prompt:** "Explain why UI toolkits use a single-threaded event model, and what class of bugs it prevents. Compare JavaFX's Platform.runLater to Swing's SwingUtilities.invokeLater."

**Symptom: UI freezes on connect**
You're doing I/O on the FX thread.
**Prompt:** "Explain `javafx.concurrent.Task` — its lifecycle, its `updateMessage`/`updateProgress` methods, and why those exist instead of letting me set properties directly from the background thread."

**Symptom: app window closes but the process stays alive**
Non-daemon reader thread still running.
**Prompt:** "Explain daemon threads and JVM shutdown semantics. Why does one non-daemon thread keep the JVM alive, and what are the two ways to fix it?"

---

## Phase 8 — Message encryption

**Read this warning first:** what you build here is a *learning exercise*, not real security. Be honest about that in your README and in interviews. Saying "I implemented AES-GCM for message confidentiality, but the key exchange is out of scope — a production system would need TLS or an authenticated Diffie–Hellman handshake, because my current design is vulnerable to man-in-the-middle" is a *far* stronger interview answer than pretending it's secure.

### Concepts first

1. **Symmetric vs asymmetric.** AES (fast, one shared key) vs RSA/ECDH (slow, key pairs). Real systems use asymmetric to exchange a symmetric key. Know why.
2. **AES modes.** ECB is broken — identical plaintext blocks produce identical ciphertext (look up the ECB penguin image, it'll stick). CBC needs a random IV per message and gives no integrity. **GCM** gives confidentiality *and* authentication. Use `AES/GCM/NoPadding`.
3. **IV/nonce rules.** Never reuse an IV with the same key in GCM. Reuse is catastrophic — it leaks plaintext relationships and lets an attacker forge messages. Generate 12 random bytes per message with `SecureRandom` and prepend them to the ciphertext.
4. **Key derivation.** Never use a password directly as a key. Use PBKDF2 (`PBKDF2WithHmacSHA256`) with a salt and ≥100,000 iterations, or better, don't do passwords at all.
5. **Encoding for a text protocol.** Ciphertext is arbitrary bytes; your protocol is newline-delimited text. Base64-encode. Know that Base64 is *encoding*, not encryption, and be able to explain the difference crisply — this is a common interview trap.
6. **Threat model.** Write down what you're defending against. Passive network eavesdropper? Yes. Malicious server? No — your server decrypts to route. Active MITM? No. Being able to state your threat model is the actual skill.

### Self-check
- Why does encrypting the same message twice produce different ciphertext with GCM but identical ciphertext with ECB?
- Your server needs the nickname in plaintext to route a PM. What does that mean for end-to-end encryption? (This tension is the whole reason Signal's protocol is hard. Discuss it in your README.)
- Is Base64 encryption? Why do people confuse them?

### Build tasks

**`common/crypto/MessageCipher.java`**
```
public class MessageCipher {
    public MessageCipher(SecretKey key)
    public String encrypt(String plaintext)    // returns base64(iv || ciphertext || tag)
    public String decrypt(String payload)      // throws AEADBadTagException on tamper
}
```

**`common/crypto/KeyUtils.java`**
```
public static SecretKey generateAesKey(int bits)                 // 128 or 256, SecureRandom
public static SecretKey deriveFromPassphrase(char[] pw, byte[] salt, int iterations)
```

Integrate: encrypt only the `body` field, not the whole protocol line — the server needs the verb and target to route. Document this design decision and its security implication explicitly.

**Tamper test:** flip one bit in a base64 payload and confirm `decrypt` throws `AEADBadTagException`. Handle it as a protocol error, log it, don't crash. Write this as an actual JUnit test.

### Stretch goal (do this if you have time — it's a big interview differentiator)
Implement ECDH key agreement in the handshake: each side generates an EC key pair, exchanges public keys, derives a shared secret with `KeyAgreement`, runs it through HKDF or SHA-256 to get an AES key. Then explicitly write in your README: *"This is still MITM-vulnerable because the public keys are unauthenticated. Authenticating them would require certificates or a trust-on-first-use fingerprint model."* That sentence is worth more than the code.

### Checkpoint
Run Wireshark or `tcpdump -i lo0 -A port 5000` while chatting. You can see the protocol verbs but the message bodies are unreadable base64. Screenshot this — it's your best demo artifact.

### When it breaks

**Symptom: `InvalidKeyException: Illegal key size`**
Old JDKs restricted AES-256 without the JCE unlimited policy files.
**Prompt:** "Explain the history of JCE unlimited strength policy files, why the restriction existed, and when it was removed by default."

**Symptom: `AEADBadTagException` on every message**
Almost always IV mismatch — you're not transmitting the IV, or you're slicing the byte array wrong.
**Prompt:** "Explain the exact byte layout of an AES-GCM output: nonce, ciphertext, and authentication tag. Where does the Java `Cipher` API place the tag, and how do `GCMParameterSpec`'s tLen parameter and the output length relate? Explain the layout, don't write the slicing code."

**Symptom: decryption produces garbage instead of throwing**
You used CBC, not GCM. CBC has no integrity check.
**Prompt:** "Explain what 'authenticated encryption' means and why CBC without a separate MAC is considered dangerous. What is the padding oracle attack?"

---

## Phase 9 — Hardening and shutdown

### Build tasks
- **Graceful shutdown.** `Runtime.getRuntime().addShutdownHook(...)` on the server: broadcast `LEFT server-shutdown`, flush logs, close the `ServerSocket`, `pool.shutdown()` then `awaitTermination(10s)` then `shutdownNow()`. Understand why all three calls are needed.
- **Heartbeat.** `PING`/`PONG` every 30s. Disconnect a client that misses two. This detects half-open connections that TCP alone won't tell you about (unplugged cable scenario).
- **Configuration.** Port, max clients, message size cap, rate limits — read from a properties file, not hardcoded.
- **Resource audit.** Run the server, connect and disconnect 200 clients in a loop with a script, then check that thread count and file descriptor count return to baseline. Leaks here are the #1 real-world server bug.

### Checkpoint
Connect/disconnect 200 clients in a script. Thread count and open FDs return to baseline. Server still healthy.

### When it breaks
**Prompt:** "Explain what a file descriptor leak is in a Java network server, the typical causes, and how to diagnose one using `lsof`, `jcmd`, and JVM thread dumps."
**Prompt:** "Explain the difference between `ExecutorService.shutdown()`, `shutdownNow()`, and `awaitTermination()`, and why a correct shutdown sequence uses all three."

---

## Phase 10 — Deliverables and packaging

### Build tasks
- **README.md** containing:
  - One-paragraph description and a screenshot
  - Architecture diagram (draw it — even ASCII is fine): acceptor thread → handler threads → registry → outbound queues
  - Build and run instructions that a stranger can follow
  - `PROTOCOL.md` link
  - **A "Design Decisions" section** — this is the part interviewers read. For each: what you chose, what you rejected, why. Cover: thread-per-connection vs NIO, the outbound queue design, why AES-GCM, why newline framing.
  - **A "Known Limitations" section.** Honest. MITM vulnerability, no persistence, no authentication, ~200 client ceiling.
- **Screenshots** (`docs/screenshots/`): two clients chatting, private message with a third client visibly excluded, server console log, connection log file, Wireshark capture showing encrypted payloads.
- **Tests.** JUnit 5. At minimum: `Message.parse`/`serialize` round-trip including edge cases, `MessageCipher` round-trip + tamper detection, `ClientRegistry` concurrent registration (spawn 50 threads racing for one nickname, assert exactly one wins).
- **Runnable jar** via the Maven Shade or Assembly plugin, plus a `run-server.sh` / `run-client.sh`.

---

## Interview preparation

Be ready for these. Write your answers in `LEARNING-LOG.md` before an interview.

**Networking**
1. Walk me through what happens from `new Socket(host, port)` to bytes arriving at the server.
2. Why does TCP not preserve message boundaries? How did you solve that?
3. TCP vs UDP — which did you pick and why? When would UDP be right for chat?
4. What is a half-open connection and how did you detect it?

**Concurrency**
5. Why thread-per-connection? What breaks at scale? What's the alternative?
6. Which collection did you use for the client registry and why not the obvious alternatives?
7. Explain a race condition you actually hit in this project and how you fixed it. *(If you can't answer this from real experience, you didn't build it yourself.)*
8. What is the slow consumer problem and how does your design handle it?
9. Explain `volatile` vs `synchronized` vs `AtomicInteger` with an example from your code.

**JavaFX**
10. Why can't you update the UI from a background thread?
11. What is `Platform.runLater` doing under the hood?

**Security**
12. Why AES-GCM over AES-CBC?
13. What's wrong with reusing an IV?
14. Is your chat end-to-end encrypted? *(Correct answer: no, and here's exactly why.)*
15. Is Base64 encryption?

**Design**
16. How would you add message persistence? Which DB and why?
17. How would you scale to 100,000 concurrent users? *(Talk about NIO/Netty, virtual threads, horizontal scaling with a message broker like Redis pub/sub or Kafka, sticky sessions.)*
18. How would you add message delivery guarantees for offline users?

---

## Progress tracker

- [ ] Phase 0 — Setup, mental model
- [ ] Phase 1 — Echo server, `nc` verified
- [ ] Phase 2 — Multi-client, thread pool, isolated failures
- [ ] Phase 3 — Registry, broadcast, slow consumer solved
- [ ] Phase 4 — `PROTOCOL.md` written and driven by hand
- [ ] Phase 5 — Nicknames, join/leave, connection log, rate limit
- [ ] Phase 6 — Private messaging with verified privacy
- [ ] Phase 7 — JavaFX client, no FX-thread violations
- [ ] Phase 8 — AES-GCM, tamper test, Wireshark screenshot
- [ ] Phase 9 — Graceful shutdown, heartbeat, leak audit
- [ ] Phase 10 — README, tests, screenshots, runnable jar
- [ ] Interview answers written for all 18 questions
