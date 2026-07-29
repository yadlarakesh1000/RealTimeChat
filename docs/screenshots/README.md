# Screenshots

Five pictures, in this order. They are the demo: somebody who never runs the code should be
able to scroll this folder and see that it works.

Save them as PNG with exactly the file names below — `README.md` links to these names.

| # | File | What it shows |
|---|---|---|
| 1 | `01-two-clients-chatting.png` | Two client windows side by side, the same conversation in both |
| 2 | `02-private-message.png` | Three windows: a whisper between two, and the third visibly **not** showing it |
| 3 | `03-server-console.png` | The server terminal: startup banner, `JOINED`, `WHISPER`, `LEFT` |
| 4 | `04-connection-log.png` | `logs/connections.log` open in an editor |
| 5 | `05-wireshark-encrypted.png` | A packet capture with the verbs readable and the bodies unreadable Base64 |

---

## How to capture each one

Windows: **Win + Shift + S** grabs a region. macOS: **Cmd + Shift + 4**. Crop to the windows,
not the whole desktop — nobody needs your taskbar.

### 1 — Two clients chatting

```bash
./run-server.sh
```

Then two clients, in two more terminals:

```bash
./run-client.sh
```

Connect as `alice` and `bob`, send a few messages each way, put the windows side by side and
capture both at once. Make sure the user list shows both names — that is the detail that
proves it is one server and not two screenshots of the same window.

### 2 — Private message, with a witness

Start a **third** client, `carol`, and leave it visible. In alice's window:

```
/pm bob this one is just for you
```

Capture all three windows. Alice shows `→ bob`, bob shows `bob ← alice`, and **carol's window
shows nothing new**. Carol is the whole point of the picture; without her it proves nothing.

### 3 — Server console

The same server terminal, scrolled so the startup banner and a few event lines are both
visible. Let a client disconnect first so there is a `LEFT` line in the shot.

### 4 — Connection log

```bash
notepad logs\connections.log
```

Show a few complete rows including the duration and reason columns — ideally one `DISCONNECT`
and one `KICKED` or `HANDSHAKE_FAIL`, so the "who ended it" distinction is visible.

### 5 — Wireshark, the money shot

Start the server **with a passphrase**, and give the same one to both clients:

```bash
./run-server.sh -Dchat.passphrase="open sesame"
```

In Wireshark, capture on the loopback interface (`Adapter for loopback traffic capture` on
Windows, `lo0` on macOS) with this display filter:

```
tcp.port == 5000
```

Send a message, right-click the packet → **Follow → TCP Stream**. Capture that window. What
makes the picture worth having is the *contrast* in one frame:

```
CHAT alice 2026-07-29T10:14:02Z 6mJ1pQ...base64...==
^^^^ ^^^^^ ^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^
readable — the server routes on it   unreadable
```

That single line is the honest summary of this project's security: the message is protected,
and who sent it to whom, when, is not.

> If loopback capture will not work on your machine, `tcpdump -i lo0 -A port 5000` on
> macOS/Linux prints the same thing to a terminal, and a terminal screenshot is fine.
