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
package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks a related track to keep playing when the queue runs dry. Uses YouTube's "mix" (radio) playlist seeded by
 * the track that just ended; for non-YouTube tracks the seed is found with a YouTube search first.
 */
final class Autoplay
{
    private static final Logger LOG = LoggerFactory.getLogger("Autoplay");
    private static final long MAX_LENGTH_MS = 15 * 60 * 1000L;

    private Autoplay() {}

    /** Calls back with the chosen track, or null if nothing suitable was found. Never throws. */
    static void findNext(PlayerManager pm, long guildId, AudioTrack last, Set<String> avoid, Consumer<AudioTrack> callback)
    {
        try
        {
            if("youtube".equals(last.getSourceManager() == null ? "" : last.getSourceManager().getSourceName()))
                loadMix(pm, guildId, last.getIdentifier(), avoid, callback);
            else
            {
                String query = "ytsearch:" + last.getInfo().author + " " + last.getInfo().title;
                pm.loadItemOrdered(guildId, query, handler(first ->
                {
                    if(first == null)
                        callback.accept(null);
                    else
                        loadMix(pm, guildId, first.getIdentifier(), avoid, callback);
                }, null));
            }
        }
        catch(Exception e)
        {
            LOG.warn("Autoplay lookup failed: {}", e.toString());
            callback.accept(null);
        }
    }

    private static void loadMix(PlayerManager pm, long guildId, String videoId, Set<String> avoid, Consumer<AudioTrack> callback)
    {
        String url = "https://www.youtube.com/watch?v=" + videoId + "&list=RD" + videoId;
        pm.loadItemOrdered(guildId, url, handler(null, playlist ->
        {
            if(playlist == null)
            {
                callback.accept(null);
                return;
            }
            List<AudioTrack> candidates = new ArrayList<>();
            for(AudioTrack t : playlist.getTracks())
            {
                if(t.getIdentifier().equals(videoId) || avoid.contains(t.getIdentifier()))
                    continue;
                if(t.getInfo().isStream || t.getDuration() > MAX_LENGTH_MS)
                    continue;
                candidates.add(t);
                if(candidates.size() >= 10)
                    break;
            }
            callback.accept(candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
        }));
    }

    private static AudioLoadResultHandler handler(Consumer<AudioTrack> onTrack, Consumer<AudioPlaylist> onPlaylist)
    {
        return new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                if(onTrack != null)
                    onTrack.accept(track);
                else if(onPlaylist != null)
                    onPlaylist.accept(null);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                if(onPlaylist != null)
                    onPlaylist.accept(playlist);
                else if(onTrack != null)
                    onTrack.accept(playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0));
            }

            @Override
            public void noMatches()
            {
                if(onTrack != null)
                    onTrack.accept(null);
                if(onPlaylist != null)
                    onPlaylist.accept(null);
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                LOG.debug("Autoplay load failed: {}", exception.getMessage());
                noMatches();
            }
        };
    }
}
