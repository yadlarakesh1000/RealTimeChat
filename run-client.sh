#!/bin/sh
# Starts one GUI chat client from the runnable jar.  (Linux / macOS / Git Bash)
#
#   ./run-client.sh
#
# Run it once per person — every window is a separate client.
#
# The jar carries JavaFX inside it, so this works on a plain JDK with no JavaFX SDK
# installed. It only works because the manifest's Main-Class (client/Main) does NOT
# extend Application; see the javadoc on that class for why.

JAR="target/chat-client.jar"

if [ ! -f "$JAR" ]; then
  echo "No $JAR yet — building it (about a minute the first time)..."
  mvn -q -DskipTests package || exit 1
fi

exec java "$@" -jar "$JAR"
