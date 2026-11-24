# syntax=docker/dockerfile:1.7

# ---- build stage -------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# resolve dependencies first so this layer is cached until pom.xml changes
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp target/JMusicBot-*-All.jar /JMusicBot.jar

# ---- runtime stage (Ubuntu 24.04 "noble" based, Java 25 is required for DAVE voice) -------------------------------
FROM eclipse-temurin:25-jre-noble

# unprivileged user with a fixed uid/gid so bind mounts can be chowned predictably
RUN groupadd --system --gid 10001 jmusicbot \
 && useradd --system --uid 10001 --gid 10001 --home-dir /data --shell /usr/sbin/nologin jmusicbot \
 && mkdir -p /data /opt/jmusicbot \
 && chown -R 10001:10001 /data

COPY --from=build /JMusicBot.jar /opt/jmusicbot/JMusicBot.jar

# All state lives in /data: jmusicbot.db (SQLite), Playlists/, optional config.txt
WORKDIR /data
VOLUME ["/data"]

ENV JMUSICBOT_HEALTH_PORT=8080 \
    TZ=UTC

USER 10001:10001

# JVM tuned for containers; native access is needed by the DAVE (FFM) and audio libraries.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", \
            "-Dnogui=true", "-jar", "/opt/jmusicbot/JMusicBot.jar"]

# Reports healthy only while the Discord gateway is CONNECTED (checked through the bot's loopback endpoint).
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-jar", "/opt/jmusicbot/JMusicBot.jar", "healthcheck"]

LABEL org.opencontainers.image.title="JMusicBot" \
      org.opencontainers.image.description="Self-hosted Discord music bot (JDA 6, DAVE voice)" \
      org.opencontainers.image.licenses="Apache-2.0"
