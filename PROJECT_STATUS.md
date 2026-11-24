# Project status

Last updated: 2026-11-24 · Branch: `modernize/jda6-dave` · Based on jagrosh/MusicBot `859e5c5` (Apache-2.0)

This is an honest ledger of what was changed, what was **verified (with how)**, and what is **not** verified. Nothing in
the "not verified" lists should be assumed to work.

---

## 1. Root causes found (why the upstream bot's voice and playback fail)

| Symptom (upstream issues) | Root cause | Status |
|---|---|---|
| Bot joins/leaves voice in a loop, silence — *"None of the provided encryption modes are supported"* ([#1843](https://github.com/jagrosh/MusicBot/issues/1843), [#1847](https://github.com/jagrosh/MusicBot/issues/1847), [#1841](https://github.com/jagrosh/MusicBot/issues/1841)) | JDA 4.4.1 (EOL) only knows voice encryption modes Discord has since removed. | **Fixed by upgrade** to JDA 6.7.0. JDA's `ERROR_UNSUPPORTED_ENCRYPTION_MODES` is now logged loudly by `VoiceWatchdog`. *Live voice not verified — see §4.* |
| Voice connection rejected with close code **4017** | Discord enforces DAVE (E2EE) on non-Stage voice. JDA only ships a pass-through; without an implementation it is rejected. ([Discord docs](https://docs.discord.com/developers/topics/opcodes-and-status-codes), [support article](https://support.discord.com/hc/en-us/articles/38749827197591)) | **Fixed**: `club.minnced:jdave-*` 0.1.8 wired through `AudioModuleConfig.withDaveSessionFactory(...)`. Native library load **verified** on Windows x64; Linux natives are bundled but **not exercised**. Requires **Java 25**. |
| *"Sign in to confirm you're not a bot"* ([#1588](https://github.com/jagrosh/MusicBot/issues/1588)), *"No n function match"*, *player script* errors ([#1776](https://github.com/jagrosh/MusicBot/issues/1776), [#1783](https://github.com/jagrosh/MusicBot/issues/1783)), *"breaks after 2nd song"* ([#1722](https://github.com/jagrosh/MusicBot/issues/1722)) | youtube-source pinned at 1.5.2 (current: 1.18.2); datacenter IPs are challenged by YouTube. | **Partly fixed.** Library updated; OAuth/poToken options added. **Measured on this machine**: some videos play, others fail with "requires login" on *every* client (see §3). Not solvable by a version bump. |
| SoundCloud tracks fail with *"Did not detect any supported formats"* | SoundCloud changed stream formats; **upstream Lavaplayer bug** [lavalink-devs/lavaplayer#204](https://github.com/lavalink-devs/lavaplayer/issues/204) (open, 2026-11-08). | **NOT fixed** (reproduced with 2.2.7 on both search and direct URLs). The bot shows a clear message. A community patch exists in the issue but it patches decoder internals; not adopted. |
| `jda-utilities` incompatible with JDA 5/6 | Library abandoned. | Replaced by **JDA-Chewtils 2.2.1** (maintained fork, API-compatible → legacy commands kept). |
| NP thumbnails never shown | `instanceof` check used the old built-in YouTube track class, never matched youtube-source tracks. | Fixed (uses `artworkUrl` with YouTube fallback). |
| `scripts/run_jmusicbot.sh` downloaded upstream's (outdated, non-DAVE) jar every start | — | Rewritten; downloads nothing. |
| Update check compared against upstream's release numbers | — | Disabled (`updatealerts=false`, `getLatestVersion()` returns null). |

Maintained forks reviewed (for evidence, no code copied): `chrisb09/MusicBot` (JDA 6.3.1 + Chewtils 2.2.1 + Lavaplayer 2.2.7 — confirms this
stack works), `arif-banai/MusicBot` (active, slash commands + panel), `SeVile/MusicBot`, `Cosgy-Dev/JMusicBot-JP`.

## 2. Verified dependency set

Versions were read from Maven Central / Lavalink Maven on 2026-11-24, not assumed.

| Component | Version | Notes |
|---|---|---|
| Java | **25** (Temurin 25.0.4 used here) | Hard requirement of jdave (FFM API). |
| JDA | 6.7.0 | `AudioModuleConfig` + DAVE hook confirmed by `javap`. |
| jdave | 0.1.8 (api + linux-x86-64, linux-aarch64, win-x86-64, darwin) | README: "very new, tested at small scale" — test in staging. |
| JDA-Chewtils | 2.2.1 | Declares JDA 6.1.1; **compiles and starts** with 6.7.0. |
| Lavaplayer | 2.2.7 (2026-07-03) | |
| youtube-source | 1.18.2 (2026-07-27) | `useOauth2`, `Web.setPoTokenAndVisitorData` confirmed by `javap`. |
| SQLite JDBC | 3.54.1.0 | |
| Tests | JUnit 6.1.3, Mockito 5.24 | |

### Lavaplayer (embedded) vs Lavalink v4 — decision: **keep embedded Lavaplayer**

| | Embedded Lavaplayer (chosen) | Lavalink v4 |
|---|---|---|
| Deployment | one process / one container | second server (Java), config, password, network |
| DAVE | via jdave in the bot (this repo) | Lavalink ≥ **4.2.0** (2026-02-28) has DAVE — **verified from release notes** |
| Code impact | none (existing queue/handler model) | rewrite playback layer around `Lavalink-Client` 3.4.0 (voice state dispatch, player events) |
| Crash isolation / multi-bot sharing | none | **better**: audio survives bot restarts, one node serves many bots |
| YouTube fixes | bump dependency | hot-swappable plugin |
| Verdict | right size for one self-hosted bot | reasonable future option if the bot grows to many guilds/shards or YouTube churn demands plugin hot-swaps |

The Lavaplayer pipeline here is identical to what Lavalink runs, so YouTube/SoundCloud limitations in §1 apply to both.

## 3. Verification evidence

**Done and passing**
- `mvn clean verify` on JDK 25: compiles 89 files, **54 tests pass** (UrlGuard incl. IPv6/mapped/metadata addresses, rate limiter isolation,
  secret redaction, SQLite settings/history/queue persistence + per-guild isolation + migration idempotence, Spotify link/embed parsing,
  radio parsing, health endpoint over a real socket, plus the original queue/time tests migrated to JUnit 5). Shaded jar builds (~77 MB).
- Jar start-up smoke test (fake token, env-only config): config loads without `config.txt`, DB migrates, **DAVE natives load**,
  failure path is clean, no token in logs.
- **Audio stack, real network, real decode** (harness against Lavaplayer 2.2.7 / youtube-source 1.18.2, decoding Opus frames, then seeking):
  - direct MP3 URL — loads, 150 frames decoded, seek works ✅
  - YouTube `dQw4w9WgXcQ` — loads, 150 frames decoded, seek works ✅ (repeated twice)
  - YouTube `YQHsXMglC9A` / `ytsearch:daft punk one more time` — search **works**, playback **fails on all clients**
    (`ANDROID_VR: requires login`, `WEB: no supported audio streams`) ❌. Tried 8 client combinations; none worked unauthenticated → OAuth/poToken needed.
  - SoundCloud (`scsearch:` and direct URL) ❌ upstream bug (§1). Wikimedia 403s the default User-Agent (host policy, not a bot bug).
- Spotify embed-page structure **checked against live pages** (track + playlist, no credentials) and covered by fixture tests. The anonymous
  access token inside those pages is deliberately **not** used.
- radio-browser.info API reachable and parsed.
- Spotify 2026 API changes read (Dev Mode restrictions, `/tracks`→`/items`, reported move away from Client Credentials for metadata):
  the API path is therefore optional and every failure falls back to the embed data.

## 4. NOT verified — do these before trusting it in production

1. **Live Discord voice + DAVE.** No Discord test credentials were available, so no real voice connection, join, playback-in-VC, reconnect, or 24/7
   rejoin has been run. This is the most important gap. Procedure: set `JMUSICBOT_TOKEN`, `JMUSICBOT_OWNER`, `JMUSICBOT_SLASH_GUILD`, run, `/play` a
   direct MP3 link in a voice channel with two accounts, and watch for `voice connected` in the log (the `ConnectionStatus` log lines are the diagnostic).
2. Slash commands, buttons, select menus, request channel, autocomplete: written against the JDA 6 API and compiled, **never executed against Discord**.
3. Queue snapshot **restore** (track encode/decode, resume position) and 24/7 sweep: logic written, DB layer tested, end-to-end restore untested.
4. Docker image: `Dockerfile`/`docker-compose.yml` written; **Docker is not installed here, so nothing was built or run**. Base images and tags were
   confirmed to exist. Unverified specifics: Temurin JRE image contents, `tmpfs /tmp:exec` + read-only rootfs with native libs, non-root volume ownership.
5. GitHub Actions workflows: never run. CodeQL with Java 25 and action pins (`@v7` etc., taken from the latest releases API) may need adjustment.
6. Spotify **API** path with credentials (only the credential-free embed path was checked); radio *stream* playback (HTTP playback itself verified).
7. Linux/arm64 natives (jdave, lavaplayer); Stage channels; verified-bot or public-bot setups (the bot refuses to run if "Public Bot" is on — upstream policy kept).
8. Dependency vulnerability scan has not been run (CI step added). `eval` (owner-only, off by default) is unchanged and still dangerous when enabled.

## 5. What was built

| Area | Implementation |
|---|---|
| Voice | JDA 6.7.0 + jdave; `VoiceWatchdog`: backoff reconnect (3s…5min), deliberate leaves never undone, 24/7 sweep every 60s, per-guild `ConnectionStatus` logging |
| Playback | queue (fair/linear), repeat off/one/all, shuffle, seek, previous, volume, **autoplay** (YouTube mix seeded by last track; skips repeats), history, 24/7 mode, vote skip |
| Sources | YouTube, SoundCloud (blocked upstream), Bandcamp, Vimeo, Twitch, HTTP, DuncteBot sources, **radio** (radio-browser lookup or stream URL), **Spotify links → metadata only → matched to YouTube/YouTube Music by closest duration**. **No direct Spotify streaming.** |
| Commands | 28 slash commands incl. `/play /search /radio /playlist /queue /nowplaying /skip /previous /loop /seek /volume /autoplay /247 /history /lyrics /settings /help /config …`; legacy prefix/mention commands kept (`play`/`playnext` now share the new loader) |
| UI | embeds with artwork, progress bar, requester, queue stats; 2-row interactive control panel; paginated queue/history; search result select menu; **persistent request channel** (`/config requestchannel`) that re-attaches its panel after restarts |
| Persistence | SQLite (WAL, versioned migrations); settings, history (500/guild), queue snapshots; legacy `serversettings.json` auto-imported once |
| Isolation / permissions | everything keyed by guild id; DJ role / Manage Server / owner model (unchanged semantics: no DJ role ⇒ only Manage Server; set DJ role to `@everyone` to open it); button clicks need the same voice channel |
| Security | SSRF: http(s) only, no userinfo, resolved-address check **plus a DNS-resolver filter on Lavaplayer's HTTP client** (covers redirects/rebinding); local-file source **off** by default; per-user rate limiter; secrets from env/`*_FILE`, token-shaped strings redacted; health endpoint loopback-only; non-root container |
| Ops | `Dockerfile` (multi-stage, Ubuntu 24.04 JRE 25, uid 10001, HEALTHCHECK via `java -jar … healthcheck`), `docker-compose.yml` (read-only rootfs, cap_drop ALL, no published ports, named volume), `.env.example`, workflows: build+test+container check, CodeQL, Trivy (fs + image), dependency review, release (jar + SHA256 + multi-arch GHCR), Dependabot (maven/actions/docker) |
| Config | `CONFIG_FORCE_<option>` env overrides for every `config.txt` option |

## 6. Known limitations / decisions

- **YouTube on VPS IPs needs credentials** (OAuth with a throwaway account, or poToken+visitorData) — documented in `docs/DEPLOYMENT.md`.
- Typed requests in the request channel need the privileged **Message Content Intent** (`messagecontent = true`). Slash commands/buttons never do.
- The "DJ default" is intentionally the restrictive upstream behaviour.
- Rate limit: 6 actions / 10 s / user / guild by default (`ratelimit.*`).
- Not done (deferred by request): web dashboard.
- Licensing: Apache-2.0 retained (`LICENSE`), original copyright headers kept, new files carry "JMusicBot contributors" headers, `NOTICE` added.
