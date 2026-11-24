/*
 * Copyright 2026 JMusicBot contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.service;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.resolve.RadioResolver;
import com.jagrosh.jmusicbot.resolve.SpotifyResolver;
import com.jagrosh.jmusicbot.security.UrlGuard;
import com.jagrosh.jmusicbot.ui.Embeds;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns whatever a user typed (URL, search text, Spotify link, radio name) into queued tracks.
 * Used by slash commands and the request channel; the legacy text commands keep their own flow.
 */
public class TrackLoader
{
    private static final Logger LOG = LoggerFactory.getLogger("TrackLoader");
    private static final Pattern ANY_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private static final int MAX_QUERY = 500;
    private static final long SPOTIFY_MATCH_TOLERANCE_MS = 20_000;

    /** Result of a load, ready to show. */
    public record Outcome(boolean ok, MessageEmbed embed) {}

    private final Bot bot;
    private final SpotifyResolver spotify;
    private final RadioResolver radio = new RadioResolver();

    public TrackLoader(Bot bot, SpotifyResolver spotify)
    {
        this.bot = bot;
        this.spotify = spotify;
    }

    private static Outcome fail(String text)
    {
        return new Outcome(false, Embeds.error(text));
    }

    /** Loads and queues a query. {@code done} is always called exactly once, from some worker thread. */
    public void load(Guild guild, Member member, String raw, boolean next, Consumer<Outcome> done)
    {
        String query = clean(raw);
        if(query.isEmpty())
        {
            done.accept(fail("Please provide a song name or link."));
            return;
        }
        if(query.length() > MAX_QUERY)
        {
            done.accept(fail("That query is too long."));
            return;
        }
        int max = bot.getConfig().getMaxQueueSize();
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        if(max > 0 && handler.getQueue().size() >= max)
        {
            done.accept(fail("The queue is full (" + max + " tracks)."));
            return;
        }
        handler.setAnnounceChannel(bot.getSettingsManager().getSettings(guild).getRequestChannelId());

        SpotifyResolver.Link link = SpotifyResolver.parse(query);
        if(link != null)
        {
            loadSpotify(guild, member, link, next, done);
            return;
        }
        String identifier;
        String lower = query.toLowerCase(Locale.ROOT);
        if(lower.startsWith("http://") || lower.startsWith("https://"))
        {
            try
            {
                UrlGuard.check(query);
            }
            catch(UrlGuard.UnsafeUrlException e)
            {
                done.accept(fail("I can't play that link: " + e.getMessage() + "."));
                return;
            }
            identifier = query;
        }
        else if(ANY_SCHEME.matcher(query).matches())
        {
            done.accept(fail("Only http and https links are supported."));
            return;
        }
        else if(lower.startsWith("ytsearch:") || lower.startsWith("ytmsearch:") || lower.startsWith("scsearch:"))
            identifier = query;
        else
            identifier = "ytsearch:" + query;
        bot.getPlayerManager().loadItemOrdered(guild.getIdLong(), identifier, new Result(guild, member, query, next, done));
    }

    private static String clean(String raw)
    {
        if(raw == null)
            return "";
        String s = raw.trim();
        if(s.startsWith("<") && s.endsWith(">") && s.length() > 2)
            s = s.substring(1, s.length() - 1);
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    // ---- radio ----

    /** Searches the radio directory (or accepts a stream URL) and plays the best match. */
    public void loadRadio(Guild guild, Member member, String raw, Consumer<Outcome> done)
    {
        String q = clean(raw);
        if(q.startsWith("http://") || q.startsWith("https://"))
        {
            load(guild, member, q, false, done);
            return;
        }
        bot.getThreadpool().submit(() ->
        {
            try
            {
                List<RadioResolver.Station> stations = radio.search(q, 5);
                if(stations.isEmpty())
                {
                    done.accept(fail("No radio station found for `" + FormatUtil.filter(q) + "`."));
                    return;
                }
                RadioResolver.Station st = stations.get(0);
                try
                {
                    UrlGuard.check(st.streamUrl());
                }
                catch(UrlGuard.UnsafeUrlException e)
                {
                    done.accept(fail("That station's stream address was rejected: " + e.getMessage() + "."));
                    return;
                }
                load(guild, member, st.streamUrl(), false, o ->
                    done.accept(o.ok() ? new Outcome(true, Embeds.ok("📻 Tuned in to **" + FormatUtil.filter(st.name()) + "**"
                            + (st.country().isBlank() ? "" : " (" + FormatUtil.filter(st.country()) + ")")
                            + (st.bitrate() > 0 ? " • " + st.codec() + " " + st.bitrate() + "kbps" : ""))) : o));
            }
            catch(Exception e)
            {
                LOG.warn("Radio lookup failed: {}", e.toString());
                done.accept(fail("The radio directory is unavailable right now. You can still paste a stream URL."));
            }
        });
    }

    // ---- Spotify (metadata only, matched to YouTube) ----

    private void loadSpotify(Guild guild, Member member, SpotifyResolver.Link link, boolean next, Consumer<Outcome> done)
    {
        bot.getThreadpool().submit(() ->
        {
            SpotifyResolver.Collection c;
            try
            {
                c = spotify.fetch(link);
            }
            catch(Exception e)
            {
                LOG.info("Spotify lookup failed for {} {}: {}", link.type(), link.id(), e.toString());
                done.accept(fail("I couldn't read that Spotify link (" + (e.getMessage() == null ? "unknown error" : e.getMessage())
                        + "). Remember I only use Spotify for song info and play the matching YouTube audio."));
                return;
            }
            List<SpotifyResolver.Item> items = c.items();
            findTrack(guild, items.get(0), first ->
            {
                if(first == null)
                {
                    done.accept(fail("I couldn't find a playable match for **" + FormatUtil.filter(items.get(0).searchQuery()) + "**."));
                    return;
                }
                AudioHandler h = bot.getPlayerManager().setUpHandler(guild);
                String q = "spotify:" + link.type() + ":" + link.id();
                int pos = enqueue(h, first, member, q, next);
                if(items.size() == 1)
                {
                    done.accept(new Outcome(true, Embeds.added(first, pos, PlaybackActions.etaMs(h, pos), member.getAsMention())));
                    return;
                }
                done.accept(new Outcome(true, Embeds.ok("Queued the first of **" + items.size() + "** tracks from "
                        + c.type() + " **" + FormatUtil.filter(c.name()) + "** — the rest are being matched to YouTube in the background.")));
                bot.getThreadpool().submit(() -> continueSpotify(guild, member, c, q));
            });
        });
    }

    private void continueSpotify(Guild guild, Member member, SpotifyResolver.Collection c, String q)
    {
        List<SpotifyResolver.Item> items = c.items();
        int max = bot.getConfig().getMaxQueueSize();
        for(int i = 1; i < items.size(); i++)
        {
            AudioHandler h = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if(h == null || (!guild.getSelfMember().getVoiceState().inAudioChannel() && h.getPlayer().getPlayingTrack() == null))
                return; // player was stopped meanwhile
            if(max > 0 && h.getQueue().size() >= max)
                return;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            findTrack(guild, items.get(i), t ->
            {
                if(t != null)
                    enqueue(h, t, member, q, false);
                latch.countDown();
            });
            try
            {
                if(!latch.await(30, TimeUnit.SECONDS))
                    LOG.debug("Spotify item {} timed out", i);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Finds the YouTube result whose length is closest to the Spotify track (YouTube Music first). */
    private void findTrack(Guild guild, SpotifyResolver.Item item, Consumer<AudioTrack> cb)
    {
        searchBest(guild, "ytmsearch:" + item.searchQuery(), item.durationMs(), t ->
        {
            if(t != null)
                cb.accept(t);
            else
                searchBest(guild, "ytsearch:" + item.searchQuery(), item.durationMs(), cb);
        });
    }

    private void searchBest(Guild guild, String identifier, long wantMs, Consumer<AudioTrack> cb)
    {
        bot.getPlayerManager().loadItemOrdered(guild.getIdLong(), identifier, new AudioLoadResultHandler()
        {
            @Override public void trackLoaded(AudioTrack track) { cb.accept(track); }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                AudioTrack best = null;
                long bestDiff = Long.MAX_VALUE;
                int n = 0;
                for(AudioTrack t : playlist.getTracks())
                {
                    if(++n > 8)
                        break;
                    long diff = wantMs <= 0 ? n : Math.abs(t.getDuration() - wantMs);
                    if(diff < bestDiff)
                    {
                        bestDiff = diff;
                        best = t;
                    }
                }
                // a result whose length is wildly off is probably a different song (live set, 10h loop...)
                if(best != null && wantMs > 0 && bestDiff > SPOTIFY_MATCH_TOLERANCE_MS * 6 && !playlist.getTracks().isEmpty())
                    best = playlist.getTracks().get(0);
                cb.accept(best);
            }

            @Override public void noMatches() { cb.accept(null); }

            @Override
            public void loadFailed(FriendlyException e)
            {
                LOG.debug("Search failed for {}: {}", identifier, e.getMessage());
                cb.accept(null);
            }
        });
    }

    // ---- shared ----

    /** @return 0 if the track started playing immediately, else its 1-based queue position. */
    private int enqueue(AudioHandler handler, AudioTrack track, Member member, String query, boolean next)
    {
        QueuedTrack qt = new QueuedTrack(track, RequestMetadata.fromUser(member.getUser(), query, track));
        return (next ? handler.addTrackToFront(qt) : handler.addTrack(qt)) + 1;
    }

    private class Result implements AudioLoadResultHandler
    {
        private final Guild guild;
        private final Member member;
        private final String query;
        private final boolean next;
        private final Consumer<Outcome> done;

        Result(Guild guild, Member member, String query, boolean next, Consumer<Outcome> done)
        {
            this.guild = guild;
            this.member = member;
            this.query = query;
            this.next = next;
            this.done = done;
        }

        private Outcome addOne(AudioTrack track)
        {
            if(bot.getConfig().isTooLong(track))
                return new Outcome(false, Embeds.warn("**" + FormatUtil.filter(track.getInfo().title) + "** is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`"));
            AudioHandler h = bot.getPlayerManager().setUpHandler(guild);
            int pos = enqueue(h, track, member, query, next);
            return new Outcome(true, Embeds.added(track, pos, PlaybackActions.etaMs(h, pos), member.getAsMention()));
        }

        @Override public void trackLoaded(AudioTrack track) { done.accept(addOne(track)); }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.getTracks().isEmpty())
            {
                done.accept(fail("That playlist is empty or couldn't be loaded."));
                return;
            }
            if(playlist.isSearchResult() || playlist.getTracks().size() == 1 || playlist.getSelectedTrack() != null)
            {
                AudioTrack single = playlist.getSelectedTrack() != null ? playlist.getSelectedTrack() : playlist.getTracks().get(0);
                done.accept(addOne(single));
                return;
            }
            AudioHandler h = bot.getPlayerManager().setUpHandler(guild);
            int max = bot.getConfig().getMaxQueueSize();
            int added = 0, skippedLong = 0;
            for(AudioTrack t : playlist.getTracks())
            {
                if(max > 0 && h.getQueue().size() >= max)
                    break;
                if(bot.getConfig().isTooLong(t))
                {
                    skippedLong++;
                    continue;
                }
                enqueue(h, t, member, query, false);
                added++;
            }
            if(added == 0)
            {
                done.accept(fail("None of the tracks could be added (too long or the queue is full)."));
                return;
            }
            String name = playlist.getName() == null ? "a playlist" : "playlist **" + FormatUtil.filter(playlist.getName()) + "**";
            done.accept(new Outcome(true, Embeds.ok("Added **" + added + "** tracks from " + name + " to the queue."
                    + (skippedLong > 0 ? "\n" + skippedLong + " were skipped for being too long." : ""))));
        }

        @Override
        public void noMatches()
        {
            done.accept(fail("No results found for `" + FormatUtil.filter(query) + "`."));
        }

        @Override
        public void loadFailed(FriendlyException e)
        {
            LOG.info("Load failed for '{}': {}", query, e.getMessage());
            String m = e.getMessage() == null ? "" : e.getMessage();
            Throwable root = e;
            while(root.getCause() != null && root.getCause() != root)
                root = root.getCause();
            if(root.getMessage() != null && root.getMessage().contains("Did not detect any supported formats"))
                done.accept(fail("SoundCloud playback is currently broken in the audio library (SoundCloud changed its stream formats; "
                        + "see lavalink-devs/lavaplayer#204). YouTube, Bandcamp and direct links still work."));
            else if(m.toLowerCase(Locale.ROOT).contains("sign in") || m.toLowerCase(Locale.ROOT).contains("not a bot"))
                done.accept(fail("YouTube is blocking this server (\"sign in to confirm you're not a bot\"). The owner needs to configure YouTube OAuth or a poToken — see docs/DEPLOYMENT.md."));
            else if(e.severity == FriendlyException.Severity.COMMON)
                done.accept(fail("Error loading: " + FormatUtil.filter(m)));
            else
                done.accept(fail("Error loading that track."));
        }
    }
}
