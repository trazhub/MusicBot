# Deployment (Debian / Ubuntu VM)

> Status: the Docker files have **not been built or run** by the author of this change (no Docker on the dev machine); see
> [PROJECT_STATUS.md](../PROJECT_STATUS.md) §4. Do a first run in a test server before relying on it.

## 1. Discord application setup

1. <https://discord.com/developers/applications> → *New Application* → **Bot** → *Reset Token* (keep it secret).
2. **Bot** page: switch **Public Bot OFF** (the bot refuses to start on public/verified bots — upstream policy).
   If you want typed song requests in a request channel or a custom text prefix, enable **Message Content Intent** and
   set `CONFIG_FORCE_messagecontent=true`. Slash commands and buttons do not need it.
3. Invite link: the bot logs one on start if it is in no server. It needs scopes `bot` + `applications.commands` and the permissions
   *View Channel, Send Messages, Embed Links, Read Message History, Connect, Speak* (add *Manage Messages* for request-channel cleanup).

## 2. Docker (recommended)

```bash
# Docker Engine + compose plugin, per https://docs.docker.com/engine/install/ubuntu/
git clone <this repo> jmusicbot && cd jmusicbot
cp .env.example .env && nano .env        # set JMUSICBOT_TOKEN and JMUSICBOT_OWNER (and JMUSICBOT_SLASH_GUILD while testing)
docker compose up -d --build
docker compose logs -f                   # look for: "DAVE ... support loaded", then "voice connected" after your first /play
docker compose ps                        # STATUS shows (healthy) once the gateway is connected
```

* State (SQLite DB, `Playlists/`, optional `config.txt`) lives in the `jmusicbot-data` volume. Back it up with
  `docker run --rm -v jmusicbot_jmusicbot-data:/d -v "$PWD":/b busybox tar czf /b/jmusicbot-data.tgz -C /d .`
* Container runs as uid 10001, read-only root filesystem, no capabilities, no published ports.
* Update: `git pull && docker compose up -d --build`. Queues are snapshotted on shutdown and restored (24/7 guilds always; others if
  stopped less than 30 minutes ago).
* Docker secrets instead of `.env`: set `JMUSICBOT_TOKEN_FILE=/run/secrets/discord_token`.
* Tagged releases publish `ghcr.io/<owner>/<repo>:<version>` (amd64 + arm64) — replace `build: .` with `image:` to use it.

## 3. Without Docker (systemd)

Requires **Java 25** (`sudo apt install openjdk-25-jre-headless` where available, or Temurin from <https://adoptium.net>).

```bash
sudo useradd --system --home /var/lib/jmusicbot --create-home --shell /usr/sbin/nologin jmusicbot
sudo install -o jmusicbot -m 0640 /dev/null /etc/jmusicbot.env && sudoedit /etc/jmusicbot.env   # same variables as .env.example
sudo cp target/JMusicBot-*-All.jar /opt/JMusicBot.jar
sudo tee /etc/systemd/system/jmusicbot.service >/dev/null <<'EOF'
[Unit]
Description=JMusicBot
After=network-online.target
Wants=network-online.target

[Service]
User=jmusicbot
WorkingDirectory=/var/lib/jmusicbot
EnvironmentFile=/etc/jmusicbot.env
ExecStart=/usr/bin/java -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED -Dnogui=true -jar /opt/JMusicBot.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/jmusicbot
PrivateTmp=false
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now jmusicbot && journalctl -u jmusicbot -f
```

(`PrivateTmp=false` because native libraries are unpacked to `/tmp` and must stay executable.)

## 4. YouTube access (important on VPS / datacenter IPs)

YouTube frequently answers datacenter IPs with *"Sign in to confirm you're not a bot"* or *"requires login"*. Measured while preparing this change:
some videos play without credentials, others fail on every client. Mitigations, in order of effort:

1. **OAuth with a throwaway Google account** (never your main one — YouTube may flag it): set `YOUTUBE_REFRESH_TOKEN=LOGIN`, start the bot,
   follow the URL/code printed in the log, then replace `LOGIN` with the refresh token printed there and restart.
2. **poToken + visitorData** (`YOUTUBE_PO_TOKEN`, `YOUTUBE_VISITOR_DATA`) — see the
   [youtube-source documentation](https://github.com/lavalink-devs/youtube-source#using-a-potoken). Tokens expire; this needs upkeep.
3. Route the VM's traffic through a residential/clean IP if neither suffices.

SoundCloud is currently broken inside Lavaplayer ([#204](https://github.com/lavalink-devs/lavaplayer/issues/204)); Bandcamp, direct links and radio work.

## 5. Spotify links

Spotify is used for **song information only**; the audio is always the matching YouTube result. Credentials are optional
(`SPOTIFY_CLIENT_ID/SECRET`); without them the bot reads Spotify's public embed data. Spotify restricted API access for new apps in 2026.

## 6. Security notes

* Never commit `.env` / `config.txt`. Tokens are redacted from logs, but treat logs as sensitive anyway.
* `allowlocalfiles` (play files from the host) and `allowprivateurls` (play from LAN) are **off**; leave them off on shared servers.
* Keep `eval=false`.
* The health endpoint is bound to `127.0.0.1` inside the container; do not publish it.
