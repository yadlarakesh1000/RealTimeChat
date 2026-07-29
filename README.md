# RealTimeChat

A multi-client chat application written in plain Java — a threaded TCP server, a JavaFX
desktop client, group and private messaging, connection logging, rate limiting, AES-GCM
encrypted message bodies, a PING/PONG heartbeat, and a graceful shutdown.

No Spring, no Netty, no WebSocket library. Sockets, threads and a text protocol I designed
myself, so that every part of it is something I can explain rather than something a framework
did for me.

![Two clients chatting](docs/screenshots/01-two-clients-chatting.png)

---

## Contents

- [What it does](#what-it-does)
- [Build and run](#build-and-run)
- [Architecture](#architecture)
- [The protocol, in one page](#the-protocol-in-one-page)
- [Security: what this actually protects](#security-what-this-actually-protects)
- [Design decisions](#design-decisions)
- [Known limitations](#known-limitations)
- [Screenshots](#screenshots)
- [Tests](#tests)
- [How it was built](#how-it-was-built)

---

## What it does

| | |
|---|---|
| **Group chat** | Everything you type goes to everyone in the room |
| **Private messages** | `/pm bob hello` reaches bob and nobody else — verified by a test with a third client watching |
| **Reply** | `/reply hi` answers whoever whispered to you last |
| **Who is online** | `/list`, plus a live user list in the GUI |
| **Join / leave notices** | Broadcast whether you quit politely, crash, or get disconnected |
| **Nickname claiming** | First come, first served, case-insensitive, atomic under a 50-thread race |
| **Rate limiting** | A token bucket per client; persistent abusers are disconnected |
| **Encryption** | AES-256/GCM on message bodies, key derived from a shared passphrase |
| **Heartbeat** | The server notices a dead socket in about 90 seconds |
| **Graceful shutdown** | Ctrl-C tells every client why, then closes cleanly |
| **Connection log** | Every connect, handshake failure, disconnect and kick, with durations and reasons |

---

## Build and run

You need **JDK 17** and **Maven**. Nothing else — JavaFX arrives as an ordinary dependency.

### The short version

```bash
mvn -DskipTests package
```

That produces two runnable jars in `target/`:

| Jar | Size | What it is |
|---|---|---|
| `chat-server.jar` | ~70 KB | The server. No JavaFX inside — it never draws anything |
| `chat-client.jar` | ~9 MB | The GUI client, with JavaFX bundled, so it runs on a plain JDK |

Then, in one terminal:

```bash
./run-server.sh
```

And one terminal per person:

```bash
./run-client.sh
```

On Windows use `run-server.bat` and `run-client.bat`. The scripts build the jar for you if it
is not there yet, so a fresh clone can start at `./run-server.sh` and nothing else.

Type a nickname, press **Connect**, and type. `/pm bob hi` is private, `/reply hi` answers the
last person who whispered to you, `/list` shows who is online. Double-clicking a name in the
user list fills in the `/pm` command for you.

### Running from Maven instead

Handy while developing, because there is no packaging step:

```bash
mvn -q exec:java      # server
mvn -q javafx:run     # client
```

### Talking to it by hand

The protocol is line-based text, so you do not need the GUI at all:

```bash
telnet localhost 5000
```

```
HELLO 1 alice
WELCOME alice rakesh-chat
MSG hello everybody
LIST
USERS alice,bob
QUIT
```

Being able to drive the server from `telnet` was a deliberate design goal from Phase 1 — it
means every bug can be reproduced without the GUI in the way.

### Configuring the server

Copy the template and edit it:

```bash
cp server.properties.example server.properties
```

```properties
port=5000
maxClients=100
pingIntervalMillis=30000
missedPongsBeforeKick=2
idleTimeoutMillis=900000
```

Every key is optional; anything you leave out keeps its default, and a missing file just means
"use the defaults". A value that is not a number where a number is expected **stops the server
with a clear message** rather than being silently ignored — a server running with a wrong
`maxClients` is worse than one that refused to start where somebody was watching.

Point it at a different file with `-Dchat.config=/path/to/other.properties`.

### Turning encryption on

```bash
./run-server.sh -Dchat.passphrase="open sesame"
```

Then type the **same passphrase** into the *Key* box of every client before connecting. The
server prints `Encryption: ON` at startup and each client's status bar says `(encrypted)` or
`(plain text)`. With no passphrase everything runs in clear text, so `telnet` still works.

> **Prefer the environment variable to the config file.** A secret in a file is one careless
> `git add .` from being public forever. `server.properties` is gitignored for exactly this
> reason, and the environment wins over the file if both are set:
>
> ```bash
> CHAT_PASSPHRASE="open sesame" ./run-server.sh
> ```

---

## Architecture

One acceptor thread, then **two threads per connected client** — one reading, one writing —
and a single shared registry that everything routes through.

```
   CLIENT (one process per person)                CLIENT
 ┌────────────────────────────────┐             ┌─────────────────┐
 │  ChatWindow  (JavaFX thread)   │             │  ChatWindow     │
 │        ▲                       │             │        ▲        │
 │        │ Platform.runLater     │             │        │        │
 │  ChatClient                    │             │  ChatClient     │
 │    ├── reader thread (daemon)  │             │    ├── reader   │
 │    └── send()  synchronized    │             │    └── send()   │
 └──────────────┬─────────────────┘             └────────┬────────┘
                │                                        │
                │   TCP :5000 — newline-delimited UTF-8 text
                └───────────────────┬────────────────────┘
                                    │
 ═══════════════════════════════════╪══════════════════ SERVER JVM ══════════
                                    ▼
                     ┌──────────────────────────┐
                     │  ChatServer              │   serverSocket.accept()
                     │  acceptor thread         │   capacity check
                     └────────────┬─────────────┘   pool.execute(handler)
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
 ┌────────────────────────────┐          ┌────────────────────────────┐
 │  ClientHandler  "alice"    │          │  ClientHandler  "bob"      │
 │                            │          │                            │
 │  reader thread             │          │  reader thread             │
 │    read line               │          │    ...                     │
 │    rate limit  (TokenBucket)          │                            │
 │    parse → decrypt         │          │                            │
 │    dispatch ───────────────┼───┐      │                            │
 │                            │   │      │                            │
 │  outbox  BlockingQueue(256)│   │  send()  ▶ outbox ──┐             │
 │       │                    │   │      │              │             │
 │       ▼                    │   │      │              ▼             │
 │  writer thread ──▶ socket  │   │      │  writer thread ──▶ socket  │
 └────────────────────────────┘   │      └────────────────────────────┘
                                  │
                    broadcast() / │ find("bob")
                                  ▼
              ┌──────────────────────────────────────────┐
              │  ClientRegistry                          │
              │  ConcurrentHashMap<lowercase nick, handler>
              └──────────────────────────────────────────┘
              ┌──────────────────────────────────────────┐
              │  ConnectionLog  ──▶  logs/connections.log │
              └──────────────────────────────────────────┘
```

**Reading it as a message's journey.** Alice types "hello". Her `ChatClient.send` writes
`MSG hello` down the socket. Alice's *reader* thread on the server reads the line, spends a
rate-limit token, parses it, decrypts the body, and calls `registry.broadcast`. Broadcast does
**not** write to any socket: it puts a line on every other client's `outbox` queue and returns.
Each client's own *writer* thread takes it from there.

That last part is the single most important design decision in the project, and
[the reason why is below](#design-decisions).

**Thread count**: `1 + 2n`, plus the pool's ceiling of `maxClients` reader slots. Twenty
clients is about 41 threads. That is fine, and it is also why the ceiling is 100 and not
100,000.

### The classes

| Package | Class | Job |
|---|---|---|
| `common` | `Message` | An immutable record; `parse()` and `serialize()` are the wire format, and they are the only place that knows it |
| | `MessageType` | The verbs, and which direction each one legally travels |
| | `ErrorCode` | The error vocabulary |
| | `BoundedLineReader` | Reads one line, refuses an over-long one, survives a read that times out mid-line |
| | `ProtocolException` | A bad message, carrying the code to answer with |
| `common.crypto` | `MessageCipher` | AES-GCM: encrypt and decrypt a string. Knows nothing about chat |
| | `KeyUtils` | Passphrase → key, via PBKDF2 |
| | `MessageCrypto` | Decides *which part* of a message is secret. The protocol-aware layer |
| `server` | `ChatServer` | Binds the port, accepts, owns the registry, the log and shutdown |
| | `ClientHandler` | One connection: reader thread, writer thread, outbox, state machine |
| | `ClientRegistry` | Who is online, by nickname. Claiming a name and broadcasting |
| | `ConnectionState` | `CONNECTED → NAMED → ACTIVE → CLOSING`, and which verbs each state permits |
| | `TokenBucket` | The rate limiter |
| | `NicknamePolicy` | What makes a nickname acceptable |
| | `ConnectionLog` | The audit file |
| | `ServerConfig` | Every setting in one place, loadable from a properties file |
| | `EchoServer` | Phase 1's single-client echo server. Kept because it is where this started |
| `client` | `ChatClient` | The networking half. **Zero JavaFX imports** — that is deliberate |
| `client.ui` | `ChatWindow` | The JavaFX half. Every update goes through `Platform.runLater` |

---

## The protocol, in one page

Newline-delimited UTF-8 text. One message per line, fields separated by single spaces, the
last field of each verb is free text and may contain spaces. Lines are capped at 4096 bytes.

**Client → server**

```
HELLO <version> <nickname>          claim a nickname; must be the first line
MSG <text>                          say something to the room
PM <nickname> <text>                say something to one person
REPLY <text>                        answer whoever whispered last
LIST                                who is online?
PONG                                yes, I am still here
QUIT                                goodbye
```

**Server → client**

```
WELCOME <nickname> <servername>     handshake accepted
CHAT <from> <timestamp> <text>      somebody spoke to the room
WHISPER <from> <timestamp> <text>   somebody whispered to you
SENT <to> <timestamp> <text>        your whisper was delivered
JOINED <nickname>                   somebody arrived
LEFT <nickname>                     somebody went
USERS <nick,nick,nick>              answer to LIST
PING                                are you still there?
ERROR <CODE> <text>                 something was wrong
```

Error codes: `MALFORMED`, `NICK_TAKEN`, `NO_SUCH_USER`, `RATE_LIMITED`, `TOO_LONG`,
`TIMEOUT`, `BAD_PAYLOAD`, `SERVER_SHUTDOWN`.

> The full specification — framing rules, the state machine, forward-compatibility rules,
> exactly which fields are encrypted — lives in `PROTOCOL.md`, which I keep alongside my
> phase notes rather than in this repository. The summary above is enough to drive the
> server by hand.

---

## Security: what this actually protects

**This is a learning exercise, not real security, and it should not be used to protect
anything that matters.** Being precise about *why* is the point of the exercise.

**What it does.** Message bodies are encrypted with **AES-256/GCM**, a fresh random 12-byte
IV per message, Base64-encoded so they survive a line-based text protocol. The key comes from
a passphrase via **PBKDF2-HMAC-SHA256**, 100,000 iterations. GCM is *authenticated*
encryption: a single altered bit is detected and the message refused, rather than decrypting
into plausible-looking garbage.

**What is deliberately left readable.** Only the body is encrypted. Verbs, nicknames, targets
and timestamps stay in clear text, because the server has to read them to route a message —
`PM bob <ciphertext>` cannot be delivered if `bob` is unreadable. So an eavesdropper still
learns **who is talking to whom, when, and how much** they said.

That tension is not an oversight. It is the reason end-to-end messaging protocols are hard,
and the honest one-sentence version is:

> *"I implemented AES-GCM for message confidentiality and integrity, but key exchange is out
> of scope — a production system would need TLS or an authenticated key agreement, because as
> it stands the design is vulnerable to a man-in-the-middle and the server itself is a trusted
> party."*

---

## Design decisions

The interesting part of the project. For each one: what I chose, what I rejected, and why.

### Thread-per-connection, not NIO

**Chosen:** one reader thread and one writer thread per client, readers drawn from a fixed
pool of `maxClients`.

**Rejected:** a single-threaded NIO `Selector` loop, or Netty.

**Why:** blocking I/O reads like the thing it does. `readLine()` blocks until there is a line;
the code below it runs when there is one. The NIO version of the same logic is a state machine
spread across callbacks, and every piece of per-connection state that was a local variable
becomes a field somebody has to remember to clean up. For a project whose purpose is to
understand what is happening, that trade is wrong.

**What it costs:** a thread is about 1 MB of stack, so this design tops out in the low
thousands of connections on a normal machine, and the fixed pool caps it at 100 on purpose.
Past that you need NIO, or Java 21's virtual threads — which are interesting here precisely
*because* they let you keep this blocking style and lose the cost.

**A fixed pool, not a cached one**, so a connection flood is refused rather than turned into
an `OutOfMemoryError`.

### An outbox queue per client, not a direct write

**Chosen:** `send()` puts a line on a bounded `BlockingQueue` and returns immediately. A
separate writer thread drains it.

**Rejected:** writing straight to the recipient's socket from whoever is broadcasting.

**Why:** this is the **slow consumer problem**, and it is the bug that would have defined the
project if I had got it wrong. TCP has flow control: if a client stops reading, its receive
window fills, then the server's send buffer fills, and `write()` **blocks**. With direct
writes, alice's thread — broadcasting her message to twenty people — would block on the one
person whose laptop went to sleep, and the entire room would freeze behind them.

With a queue, one slow client just fills their own 256-message outbox. When it overflows we
know they have genuinely stopped reading, and we disconnect them. **One slow client harms only
themselves**, which is the whole point.

**A second benefit I did not plan:** shutdown could queue a goodbye line to 100 clients
instantly, because queueing cannot block.

**What it costs:** memory, bounded at 256 messages per client, and messages can be dropped on
overflow rather than delayed forever. That is the right choice for chat — a message nobody can
receive is not worth stalling everyone else for.

### Newline framing, not length prefixes

**Chosen:** one message per line, `\n`-delimited, UTF-8.

**Rejected:** a 4-byte length prefix, or Java serialization.

**Why:** TCP is a **byte stream with no message boundaries** — `write("HELLO\n")` twice can
arrive as one `read()`, or as seven. Something has to reimpose boundaries, and a delimiter you
can type is worth a lot: I can drive the whole server from `telnet`, and every capture in
Wireshark is readable without a dissector. Java serialization was never a candidate — it
couples both ends to the same classes and is a well-known remote-code-execution vector.

**What it costs:** the delimiter cannot appear in the data. Messages are single-line, and a
4096-byte cap turns "a peer that never sends a newline" from an unbounded memory leak into a
refused connection.

### AES-GCM, not CBC and certainly not ECB

**Chosen:** `AES/GCM/NoPadding`, 256-bit key, random 12-byte IV per message, prepended to the
ciphertext.

**Rejected:** ECB (identical plaintext produces identical ciphertext — look up the ECB
penguin), CBC (needs a separate MAC or you have no integrity at all, and gives you the padding
oracle family of attacks for free).

**Why:** GCM is *authenticated* encryption. It answers both "can an eavesdropper read this"
and "can an attacker change this without me noticing", and the second question is the one
people forget. A flipped bit throws `AEADBadTagException`; the server answers `BAD_PAYLOAD`,
logs it, and keeps the connection. There is a test that flips a bit and asserts exactly that.

**The IV rule that matters:** never reuse an IV with the same key in GCM. Reuse is
catastrophic — it leaks relationships between plaintexts and makes forgery possible. So a
fresh `SecureRandom` nonce per message, which is also why encrypting the same message twice
gives two different lines.

### Encrypt the body, not the line

**Chosen:** only the free-text body of the six verbs a human types into.

**Rejected:** encrypting the whole protocol line.

**Why:** the server is a router. It must read `PM` and `bob` to know where the message goes. An
encrypted line would need a server that cannot route, which means a fundamentally different
design (per-recipient sealed envelopes, and a directory the server can still read). Choosing
this consciously, and writing down the metadata leak it causes, is worth more than pretending
the problem is not there.

### `ConcurrentHashMap` for the registry, and `putIfAbsent` for the nickname

**Chosen:** `ConcurrentHashMap<String, ClientHandler>`, keyed on the lower-cased nickname.

**Rejected:** `HashMap` + `synchronized` (every broadcast queues behind the same lock);
`Collections.synchronizedMap` (same problem, plus check-then-act is still not atomic).

**Why:** claiming a nickname is exactly `putIfAbsent` — one atomic operation that both checks
and claims. The obvious version, `if (!map.containsKey(n)) map.put(n, h)`, has a window
between the two calls in which somebody else can claim it, and two users end up sharing a
name. There is a test that races 50 threads at one nickname and asserts exactly one wins.

Unregistration is conditional — `remove(key, this)` — so a handler cleaning up late cannot
evict whoever has since taken the name.

### `PONG` does not count as activity

**Chosen:** the heartbeat proves the *socket* is alive; only a real message proves a *person*
is there. They reset different clocks.

**Rejected:** treating a `PONG` like any other line.

**Why:** the obvious version compiles, passes every obvious test, and silently deletes the idle
timeout — because the client answers heartbeats by itself, forever, so a window left open
overnight holds its nickname until the server restarts. It is three lines of code and it is my
favourite thing in the project, because the bug it avoids leaves no trace.

### Settings in a properties file

**Chosen:** `java.util.Properties`, every key optional, a bad value throws.

**Rejected:** YAML or JSON with a library (a dependency for something the JDK already does);
logging a warning and carrying on with the default (a server running with a silently wrong
limit is worse than one that refused to start).

### A new error code, not a new field on `LEFT`

When shutdown needed to tell clients why, the obvious move was `LEFT <nick> server-shutdown`.
But `LEFT`'s nickname field is the *last* field, so appending to it is a **breaking** change
for any parser that reads to end-of-line. Adding `ERROR SERVER_SHUTDOWN` is additive and needs
no version bump. Same information, no compatibility cost.

### Two jars, not one

The server jar excludes JavaFX, because a server that never draws a pixel should not carry
9 MB of UI toolkit. The client's `Main-Class` is a launcher that does **not** extend
`Application`, which is what lets the fat jar run from a plain classpath instead of demanding
the module path.

---

## Known limitations

Honest, and each one is a choice rather than an accident.

| Limitation | Consequence |
|---|---|
| **Vulnerable to man-in-the-middle.** Neither end authenticates the other. | Somebody who can sit between client and server can impersonate either. TLS, or an authenticated key agreement, is the real answer. |
| **Not end-to-end encrypted.** The server holds the key and decrypts every message to route it. | A malicious or compromised server reads everything. |
| **No key exchange.** The passphrase is typed into both ends by a human. | Nothing to attack, but nothing that scales past people who can talk in person either. |
| **One shared group key**, fixed salt, no rotation. | No forward secrecy: one leaked passphrase exposes every past and future message. |
| **Metadata is not protected.** | Verbs, nicknames and timestamps are plain text on the wire, so traffic analysis still reveals the social graph. |
| **No authentication at all.** Any nickname that is free is yours. | There are no accounts, no passwords, and nothing stops impersonation between sessions. |
| **No persistence.** | Restart the server and the history is gone; there was never any history. Messages for an offline user are simply not delivered. |
| **~100 concurrent clients.** Two threads each, plus a deliberate `maxClients` cap. | Thread-per-connection does not go much further. NIO, virtual threads, or several servers behind a message broker is the next step. |
| **One server, no clustering.** | It is a single point of failure and a single point of scaling. |
| **The heartbeat is one-directional.** | The server detects a dead client in ~90 s; a client only notices a dead server when it next tries to send. |
| **The client does not reconnect.** | If the connection drops you press Connect again. |
| **The heartbeat is not negotiated.** | A pre-Phase-9 client would be dropped after 90 seconds for not answering a verb it does not know. Both ends ship together, so nobody is affected in practice. |
| **The shutdown grace period is a fixed 200 ms.** | Enough on loopback, possibly not over a bad network. A very slow client can miss the goodbye and see a plain disconnect. |
| **The leak audit counts threads, not file descriptors.** | Java has no portable API for the descriptor count, so the 200-connection test asserts threads and connection counts return to baseline and infers the rest. |
| **The passphrase can be put in a config file.** | Supported, gitignored, and the environment variable overrides it — but nothing physically stops you. A real system uses a secret manager. |

---

## Screenshots

In [`docs/screenshots/`](docs/screenshots/). The capture instructions are in that folder's
README, including the Wireshark filter for the last one.

| | |
|---|---|
| ![Two clients chatting](docs/screenshots/01-two-clients-chatting.png) | **Two clients**, one conversation |
| ![Private message](docs/screenshots/02-private-message.png) | **A private message** — three clients, and the third one visibly does not see it |
| ![Server console](docs/screenshots/03-server-console.png) | **The server console**: startup banner and connection events |
| ![Connection log](docs/screenshots/04-connection-log.png) | **The connection log**, with durations and close reasons |
| ![Wireshark](docs/screenshots/05-wireshark-encrypted.png) | **Wireshark on the loopback**: the verbs readable, the bodies not |

---

## Tests

```bash
mvn -B test
```

**296 tests**, JUnit 5, no display required — the client tests drive `ChatClient` directly and
never open a window, which is what a headless CI would need.

The ones worth reading:

| Test | What it pins down |
|---|---|
| `MessageTest.everyMessageTypeRoundTrips` | Every verb survives `serialize` → `parse` unchanged. Asserts the set covers *every* `MessageType` in declaration order, so a new verb cannot be added without a round-trip test |
| `MessageCipherTest` tamper cases | Flip one bit of a Base64 payload → `AEADBadTagException`, handled as a protocol error rather than a crash |
| `ClientRegistryTest.testConcurrentRegistrationRace` | 50 threads race for one nickname; exactly one wins |
| `Phase6Test` privacy cases | A third client is connected and asserted to receive **nothing** during a private exchange |
| `Phase9Test.twoHundredConnectionsLeaveNothingBehind` | 200 connect/disconnect cycles; thread and connection counts return to baseline |
| `Phase9Test.pongDoesNotCountAsActivity` | The three lines described above, so nobody can "tidy them up" |
| `Phase10Test.everyTextFileWeShipIsUtf8` | Because this README was once accidentally UTF-16, which git treats as a binary file |

---

## How it was built

Ten phases, each one building on the last, each with its own concepts document, build report
and honest list of the bugs it caused:

| Phase | What it added |
|---|---|
| 0–1 | Setup, and a single-client echo server driven from `nc` |
| 2 | Multiple clients, a thread pool, one client's failure isolated from the rest |
| 3 | The registry, broadcast, and the slow-consumer problem solved with outbox queues |
| 4 | The protocol written down first, then implemented and driven by hand |
| 5 | Nicknames, join/leave notices, the connection log, rate limiting |
| 6 | Private messaging, with privacy proved by a watching third client |
| 7 | The JavaFX client, with no FX-thread violations |
| 8 | AES-GCM encryption, the tamper test, the Wireshark capture |
| 9 | Heartbeat, graceful shutdown, settings from a file, the leak audit |
| 10 | This README, the runnable jars, the run scripts, the deliverable tests |

The phase documents (`PHASE-n-CONCEPTS.md`, `PHASE-n-REPORT.md`), the protocol specification
and `LEARNING-LOG.md` are notes I keep locally rather than in the repository.

---

*Built by Rakesh Yadla as a learning project. The limitations above are the part I am most
willing to talk about.*
