/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.db.QueueStore;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.ui.Embeds;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild audio state: the player, the queue, playback history, autoplay and the Discord send handler.
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler
{
    private static final Logger LOG = LoggerFactory.getLogger("AudioHandler");
    private static final int PREVIOUS_LIMIT = 25;

    public final static String PLAY_EMOJI  = "▶"; // ▶
    public final static String PAUSE_EMOJI = "⏸"; // ⏸
    public final static String STOP_EMOJI  = "⏹"; // ⏹

    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();
    private final Deque<AudioTrack> previousTracks = new ArrayDeque<>();
    private final Deque<String> recentIds = new ArrayDeque<>();

    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;

    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;
    private volatile boolean stopping = false;
    private volatile boolean intentionalLeave = false;
    private volatile boolean autoplaySearching = false;
    private volatile long announceChannelId = 0;
    private volatile long lastVoiceChannelId = 0;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();
        this.setQueueType(manager.getBot().getSettingsManager().getSettings(guildId).getQueueType());
    }

    public void setQueueType(QueueType type)
    {
        queue = type.createInstance(queue);
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            queue.addAt(0, qtrack);
            return 0;
        }
    }

    public int addTrack(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
            return queue.add(qtrack);
    }

    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }

    public void stopAndClear()
    {
        stopping = true;
        try
        {
            queue.clear();
            defaultQueue.clear();
            audioPlayer.stopTrack();
        }
        finally
        {
            stopping = false;
        }
        manager.getBot().getQueueStore().clear(guildId);
    }

    /** Disconnects from voice on purpose, so the reconnect watchdog doesn't undo it. */
    public void disconnect()
    {
        intentionalLeave = true;
        Guild guild = manager.getBot().getJDA().getGuildById(guildId);
        if(guild != null)
            guild.getAudioManager().closeAudioConnection();
    }

    public void markIntentionalLeave()
    {
        intentionalLeave = true;
    }

    public boolean isIntentionalLeave()
    {
        return intentionalLeave;
    }

    /** Called when the bot (re)joins a voice channel. */
    public void onJoined(long voiceChannelId)
    {
        intentionalLeave = false;
        lastVoiceChannelId = voiceChannelId;
    }

    public long getLastVoiceChannelId()
    {
        return lastVoiceChannelId;
    }

    public void setAnnounceChannel(long channelId)
    {
        if(channelId != 0)
            this.announceChannelId = channelId;
    }

    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inAudioChannel() && audioPlayer.getPlayingTrack()!=null;
    }

    public Set<String> getVotes()
    {
        return votes;
    }

    public AudioPlayer getPlayer()
    {
        return audioPlayer;
    }

    public long getGuildId()
    {
        return guildId;
    }

    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }

    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
            return false;

        Playlist pl = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(pl==null || pl.getItems().isEmpty())
            return false;
        pl.loadTracks(manager, (at) ->
        {
            if(audioPlayer.getPlayingTrack()==null)
                audioPlayer.playTrack(at);
            else
                defaultQueue.add(at);
        }, () ->
        {
            if(pl.getTracks().isEmpty() && !stayConnected())
                disconnectIfAllowed();
        });
        return true;
    }

    /** True if the bot should remain in the voice channel when idle (global config or per-guild 24/7 mode). */
    public boolean stayConnected()
    {
        return manager.getBot().getConfig().getStay() || manager.getBot().getSettingsManager().getSettings(guildId).isStay247();
    }

    private void disconnectIfAllowed()
    {
        if(!stayConnected())
        {
            intentionalLeave = true;
            manager.getBot().closeAudioConnection(guildId);
        }
    }

    /** Previously played tracks, most recent first (clones that can be replayed). */
    public List<AudioTrack> getPreviousTracks()
    {
        synchronized(previousTracks)
        {
            return new ArrayList<>(previousTracks);
        }
    }

    /** Replays the most recently finished track next and skips the current one. @return false if there is none. */
    public boolean playPrevious()
    {
        AudioTrack prev;
        synchronized(previousTracks)
        {
            prev = previousTracks.pollFirst();
        }
        if(prev == null)
            return false;
        RequestMetadata rm = prev.getUserData(RequestMetadata.class);
        QueuedTrack qt = new QueuedTrack(prev, rm == null ? RequestMetadata.EMPTY : rm);
        if(audioPlayer.getPlayingTrack() == null)
            audioPlayer.playTrack(qt.getTrack());
        else
        {
            queue.addAt(0, qt);
            audioPlayer.stopTrack();
        }
        return true;
    }

    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();

        if(endReason != AudioTrackEndReason.REPLACED && endReason != AudioTrackEndReason.CLEANUP && track != null)
        {
            AudioTrack copy = track.makeClone();
            copy.setUserData(track.getUserData(RequestMetadata.class));
            synchronized(previousTracks)
            {
                previousTracks.addFirst(copy);
                while(previousTracks.size() > PREVIOUS_LIMIT)
                    previousTracks.pollLast();
            }
        }

        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
                queue.add(clone);
            else
                queue.addAt(0, clone);
        }

        if(queue.isEmpty())
        {
            if(!stopping && endReason.mayStartNext && tryAutoplay(track))
                return; // autoplay continues asynchronously
            if(!playFromDefault())
                finishIdle(player);
        }
        else
        {
            QueuedTrack qt = queue.pull();
            player.playTrack(qt.getTrack());
        }
    }

    private void finishIdle(AudioPlayer player)
    {
        manager.getBot().getNowplayingHandler().onTrackUpdate(null);
        manager.getBot().getRequestChannels().refresh(guildId);
        disconnectIfAllowed();
        // unpause, in the case when the player was paused and the track has been skipped.
        // this is to prevent the player being paused next time it's being used.
        player.setPaused(false);
    }

    private boolean tryAutoplay(AudioTrack last)
    {
        if(last == null || autoplaySearching || !manager.getBot().getSettingsManager().getSettings(guildId).isAutoplay())
            return false;
        autoplaySearching = true;
        Set<String> avoid;
        synchronized(recentIds)
        {
            avoid = new HashSet<>(recentIds);
        }
        Autoplay.findNext(manager, guildId, last, avoid, next ->
        {
            autoplaySearching = false;
            if(next != null && audioPlayer.getPlayingTrack() == null && queue.isEmpty())
            {
                audioPlayer.playTrack(new QueuedTrack(next, RequestMetadata.EMPTY).getTrack());
            }
            else if(audioPlayer.getPlayingTrack() == null)
            {
                if(queue.isEmpty())
                    finishIdle(audioPlayer);
                else
                    audioPlayer.playTrack(queue.pull().getTrack());
            }
        });
        return true;
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception)
    {
        LOG.error("Track " + track.getIdentifier() + " has failed to play", exception);
        announce(Embeds.error("Couldn't play **" + track.getInfo().title + "**: " + friendly(exception)));
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        LOG.warn("Track {} got stuck for {}ms, skipping", track.getIdentifier(), thresholdMs);
        announce(Embeds.warn("**" + track.getInfo().title + "** got stuck, skipping it."));
        player.stopTrack();
    }

    private static String friendly(FriendlyException e)
    {
        String m = e.getMessage() == null ? "unknown error" : e.getMessage();
        if(m.toLowerCase().contains("sign in") || m.toLowerCase().contains("not a bot"))
            return "YouTube is asking this server to sign in. The owner needs to set up YouTube OAuth or a poToken (see docs/DEPLOYMENT.md).";
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }

    private void announce(net.dv8tion.jda.api.entities.MessageEmbed embed)
    {
        try
        {
            Guild guild = manager.getBot().getJDA().getGuildById(guildId);
            if(guild == null)
                return;
            TextChannel tc = announceChannelId == 0 ? null : guild.getTextChannelById(announceChannelId);
            if(tc == null)
                tc = manager.getBot().getSettingsManager().getSettings(guildId).getTextChannel(guild);
            if(tc != null && tc.canTalk())
                tc.sendMessageEmbeds(embed).queue(null, t -> {});
        }
        catch(Exception e)
        {
            LOG.debug("Could not announce: {}", e.toString());
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track)
    {
        votes.clear();
        synchronized(recentIds)
        {
            recentIds.addFirst(track.getIdentifier());
            while(recentIds.size() > 40)
                recentIds.pollLast();
        }
        RequestMetadata rm = track.getUserData(RequestMetadata.class);
        manager.getBot().getHistoryStore().record(guildId, rm == null ? 0 : rm.getOwner(), track.getInfo().title,
                track.getInfo().author, track.getInfo().uri, track.getInfo().isStream ? 0 : track.getDuration());
        manager.getBot().getNowplayingHandler().onTrackUpdate(track);
        manager.getBot().getRequestChannels().refresh(guildId);
    }

    @Override
    public void onPlayerPause(AudioPlayer player)
    {
        manager.getBot().getRequestChannels().refresh(guildId);
    }

    @Override
    public void onPlayerResume(AudioPlayer player)
    {
        manager.getBot().getRequestChannels().refresh(guildId);
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }

    // Persistence

    /** Builds a snapshot of this player, or null if there is nothing worth saving. */
    public QueueStore.State snapshot(long voiceChannelId)
    {
        AudioTrack current = audioPlayer.getPlayingTrack();
        List<QueuedTrack> list = queue.getList();
        if(current == null && list.isEmpty())
            return null;
        QueueStore.SavedTrack cur = null;
        if(current != null)
            cur = saved(current);
        List<QueueStore.SavedTrack> saved = new ArrayList<>();
        for(QueuedTrack qt : new ArrayList<>(list))
        {
            QueueStore.SavedTrack s = saved(qt.getTrack());
            if(s != null)
                saved.add(s);
        }
        return new QueueStore.State(guildId, voiceChannelId, true, current == null ? 0 : current.getPosition(),
                audioPlayer.isPaused(), cur, saved, System.currentTimeMillis());
    }

    private QueueStore.SavedTrack saved(AudioTrack track)
    {
        String data = manager.encode(track);
        if(data == null)
            return null;
        RequestMetadata rm = track.getUserData(RequestMetadata.class);
        if(rm == null || rm.user == null)
            return new QueueStore.SavedTrack(data, 0, null, null, null);
        return new QueueStore.SavedTrack(data, rm.user.id, rm.user.username, rm.user.avatar,
                rm.requestInfo == null ? null : rm.requestInfo.query);
    }

    /** Restores a snapshot made by {@link #snapshot}; the current track resumes at its saved position. */
    public int restore(QueueStore.State state)
    {
        int count = 0;
        for(QueueStore.SavedTrack s : state.queue())
        {
            AudioTrack t = manager.decode(s.data());
            if(t != null)
            {
                queue.add(new QueuedTrack(t, RequestMetadata.restored(s.requesterId(), s.requesterName(), s.requesterAvatar(), s.query(), t)));
                count++;
            }
        }
        if(state.current() != null && audioPlayer.getPlayingTrack() == null)
        {
            AudioTrack t = manager.decode(state.current().data());
            if(t != null)
            {
                QueueStore.SavedTrack s = state.current();
                QueuedTrack qt = new QueuedTrack(t, RequestMetadata.restored(s.requesterId(), s.requesterName(), s.requesterAvatar(), s.query(), t));
                if(qt.getTrack().isSeekable() && state.positionMs() > 0)
                    qt.getTrack().setPosition(state.positionMs());
                audioPlayer.playTrack(qt.getTrack());
                audioPlayer.setPaused(state.paused());
                count++;
            }
        }
        else if(audioPlayer.getPlayingTrack() == null && !queue.isEmpty())
            audioPlayer.playTrack(queue.pull().getTrack());
        return count;
    }

    // Audio Send Handler methods
    @Override
    public boolean canProvide()
    {
        lastFrame = audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio()
    {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus()
    {
        return true;
    }

    // Private methods
    private Guild guild(JDA jda)
    {
        return jda.getGuildById(guildId);
    }

    /** Helper for callers that need the connected channel. */
    public AudioChannel connectedChannel(JDA jda)
    {
        Guild g = guild(jda);
        return g == null ? null : g.getAudioManager().getConnectedChannel();
    }
}
