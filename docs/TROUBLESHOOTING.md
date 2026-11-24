# Troubleshooting

Start with the log. Voice problems are logged per guild by `VoiceWatchdog`:

| Log line | Meaning | What to do |
|---|---|---|
| `DAVE support loaded` | jdave native library loaded | good |
| `DAVE support could NOT be loaded` | unsupported OS/CPU or Java < 25 | use Java 25 on linux x86-64/aarch64, windows x86-64 or macOS; Alpine/musl is not supported by jdave |
| `[guild] voice connected` | voice handshake finished | good |
| `voice connection error: ERROR_UNSUPPORTED_ENCRYPTION_MODES` | outdated JDA | update the bot (this fork ships JDA 6.7.0) |
| `ERROR_CANNOT_RESUME` / `ERROR_LOST_CONNECTION` | network or Discord voice server problem | the watchdog retries (3s, 10s, 30s, 1m, 2m, 5m); try another server region in channel settings |
| `ERROR_UDP_UNABLE_TO_CONNECT` | outbound UDP blocked | allow outbound UDP (voice uses random high ports) |
| close code **4017** | DAVE not negotiated | confirm the DAVE line above; update jdave |
| `Track … has failed to play … sign in` | YouTube bot check | `docs/DEPLOYMENT.md` §4 |
| `Did not detect any supported formats` (SoundCloud) | upstream Lavaplayer bug #204 | wait for a Lavaplayer fix; use other sources |

**Slash commands don't show up.** Global registration can take up to an hour: set `JMUSICBOT_SLASH_GUILD` to your server ID for instant
registration while testing. Make sure the bot was invited with the `applications.commands` scope (re-invite using the logged link).

**Typed requests in the request channel are ignored.** Enable *Message Content Intent* in the Developer Portal, then set
`CONFIG_FORCE_messagecontent=true`. If you set it without enabling the portal toggle, Discord rejects the login.

**"Public Bot is enabled" and the bot exits.** Turn *Public Bot* off in the Developer Portal (and remove Installation Contexts it lists).

**Bot leaves when the queue ends.** Expected unless 24/7 mode is on (`/247 enabled:true`, needs Manage Server).

**Buttons say "Only DJs can do that".** DJ commands need Manage Server or the DJ role (`/config dj`). Setting the DJ role to `@everyone` opens them to all.

**Container unhealthy.** `docker compose logs`; health is "healthy" only while the Discord gateway is connected (bad token ⇒ unhealthy/exits).

**Reset everything.** Stop the bot and delete `jmusicbot.db*` (settings, history and saved queues).
