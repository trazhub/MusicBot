#!/bin/sh
# Runs a locally built JMusicBot jar and restarts it if it exits (e.g. after the owner's shutdown command).
# Requires Java 25+. Unlike earlier versions of this script it does NOT download anything: build the jar
# (mvn package) or take one from this project's releases, and point JAR at it.
#
#   JAR=JMusicBot.jar ./run_jmusicbot.sh
JAR="${JAR:-JMusicBot.jar}"
LOOP="${LOOP:-true}"

if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR (set JAR=/path/to/JMusicBot-x.y.z.jar)" >&2
    exit 1
fi

while true; do
    java -Dnogui=true --enable-native-access=ALL-UNNAMED -jar "$JAR"
    [ "$LOOP" = "true" ] || break
    echo "JMusicBot exited, restarting in 5 seconds (Ctrl+C to stop)"
    sleep 5
done
