#!/bin/sh
# Starts the chat server from the runnable jar.  (Linux / macOS / Git Bash)
#
#   ./run-server.sh
#   ./run-server.sh -Dchat.passphrase="open sesame"
#   ./run-server.sh -Dchat.config=/etc/mychat.properties
#
# Anything you pass is handed to the JVM, which is why it goes BEFORE -jar:
# java reads its own -D options first and stops looking once it sees -jar.

JAR="target/chat-server.jar"

# Build on first run so a stranger who just cloned the repo can start here.
if [ ! -f "$JAR" ]; then
  echo "No $JAR yet — building it (about a minute the first time)..."
  mvn -q -DskipTests package || exit 1
fi

# exec replaces this shell with java, so Ctrl-C reaches the server itself and its
# shutdown hook runs. Without exec, Ctrl-C would kill the script and leave the JVM.
exec java "$@" -jar "$JAR"
