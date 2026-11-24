# JMusicBot (modernized fork)

A self-hosted Discord music bot. This is a fork of [jagrosh/MusicBot](https://github.com/jagrosh/MusicBot) (Apache-2.0) that fixes the
voice failures of the upstream 0.4.x line and adds slash commands, interactive panels, persistence and container tooling.

> **Read [PROJECT_STATUS.md](PROJECT_STATUS.md) first.** It lists exactly what was verified and what was not — notably, live Discord voice
> has not been tested by the author of this change, and YouTube/SoundCloud have known limitations.

## Why a fork
* Voice was broken upstream: JDA 4 cannot negotiate Discord's current voice encryption (reconnect loops, silence) and has no **DAVE**
  end-to-end-encryption support, which Discord now requires. This fork uses **JDA 6.7.0 + jdave** and therefore needs **Java 25**.
* Updated Lavaplayer 2.2.7 and youtube-source 1.18.2.

## Features
* **Slash commands** for everything (`/play /search /radio /playlist /queue /nowplaying /skip /previous /loop /seek /volume /shuffle /autoplay /247 /history /lyrics /settings /config /help …`) — the original `!`/mention commands still work.
* **Player panel** with artwork, progress bar and buttons (pause, skip, previous, stop, loop, shuffle, volume, queue, autoplay); paginated queue and history.
* **Request channel**: a persistent panel channel where typing a song name queues it (`/config requestchannel`).
* Queue (fair or linear), repeat, shuffle, seek, **autoplay**, history, **24/7 mode**, automatic voice reconnection, queue restored after restarts.
* Sources: YouTube, Bandcamp, Vimeo, Twitch, direct audio URLs, internet **radio** (by name), **Spotify links** (song info only — audio is matched on YouTube; Spotify is never streamed). SoundCloud is currently broken inside Lavaplayer ([#204](https://github.com/lavalink-devs/lavaplayer/issues/204)).
* SQLite persistence, per-server settings, DJ role / Manage Server permissions, rate limiting, SSRF protection, secrets via environment.
* Docker image (non-root, health check), docker-compose, GitHub Actions CI, CodeQL/Trivy scanning, releases.

## Quick start (Docker)
```bash
cp .env.example .env      # set JMUSICBOT_TOKEN and JMUSICBOT_OWNER
docker compose up -d --build
docker compose logs -f
```
Full instructions, systemd alternative, Discord setup and YouTube sign-in workarounds: **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**.
Problems: **[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)**.

## Build from source
Requires JDK 25 and Maven 3.9+.
```bash
mvn verify                       # compiles, runs the tests, builds target/JMusicBot-<version>-All.jar
java --enable-native-access=ALL-UNNAMED -jar target/JMusicBot-*-All.jar
```
Configuration: environment variables (see `.env.example`) or a `config.txt` (`java -jar … generate-config` writes a documented template).
Any option can be overridden with `CONFIG_FORCE_<option>`.

## License
Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Original work © John Grosh and contributors.
