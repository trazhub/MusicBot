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
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

/**
 * Every playback control, implemented once, with permission checks, so slash commands, panel buttons and the
 * request channel behave identically. Methods return a {@link Result} whose message is shown to the user.
 */
public class PlaybackActions
{
    public record Result(boolean ok, String message)
    {
        static Result ok(String m) { return new Result(true, m); }
        static Result fail(String m) { return new Result(false, m); }
    }

    private final Bot bot;

    public PlaybackActions(Bot bot)
    {
        this.bot = bot;
    }

    private AudioHandler handler(Guild g)
    {
        return bot.getPlayerManager().setUpHandler(g);
    }

    /** Common preconditions: listening in the bot's channel, DJ if needed, and something playing if needed. */
    private Result guard(Guild g, Member m, boolean needDJ, boolean needPlaying)
    {
        String err = Access.checkListening(bot, g, m, false);
        if(err != null)
            return Result.fail(err);
        if(needDJ && !Access.isDJ(bot, g, m))
            return Result.fail("Only DJs can do that!");
        if(needPlaying && !handler(g).isMusicPlaying(g.getJDA()))
            return Result.fail("There must be music playing to use that!");
        return null;
    }

    public Result setPaused(Guild g, Member m, boolean paused)
    {
        Result r = guard(g, m, true, true);
        if(r != null) return r;
        AudioHandler h = handler(g);
        h.getPlayer().setPaused(paused);
        return Result.ok((paused ? "Paused **" : "Resumed **") + h.getPlayer().getPlayingTrack().getInfo().title + "**.");
    }

    public Result togglePause(Guild g, Member m)
    {
        return setPaused(g, m, !handler(g).getPlayer().isPaused());
    }

    /** Vote skip (or immediate skip for the requester / when the skip ratio is 0). */
    public Result skip(Guild g, Member m)
    {
        Result r = guard(g, m, false, true);
        if(r != null) return r;
        AudioHandler h = handler(g);
        RequestMetadata rm = h.getRequestMetadata();
        double ratio = bot.getSettingsManager().getSettings(g).getSkipRatio();
        if(ratio == -1)
            ratio = bot.getConfig().getSkipRatio();
        String title = h.getPlayer().getPlayingTrack().getInfo().title;
        if(m.getIdLong() == rm.getOwner() || ratio == 0 || Access.isDJ(bot, g, m) && rm.getOwner() == 0L)
        {
            h.getPlayer().stopTrack();
            return Result.ok("Skipped **" + title + "**");
        }
        AudioChannel vc = g.getSelfMember().getVoiceState().getChannel();
        int listeners = (int) vc.getMembers().stream().filter(x -> !x.getUser().isBot() && !x.getVoiceState().isDeafened()).count();
        boolean already = h.getVotes().contains(m.getUser().getId());
        h.getVotes().add(m.getUser().getId());
        int skippers = (int) vc.getMembers().stream().filter(x -> h.getVotes().contains(x.getUser().getId())).count();
        int required = (int) Math.ceil(listeners * ratio);
        String msg = (already ? "You already voted to skip this song" : "You voted to skip the song")
                + " `[" + skippers + " votes, " + required + "/" + listeners + " needed]`";
        if(skippers >= required)
        {
            h.getPlayer().stopTrack();
            msg += "\nSkipped **" + title + "** " + (rm.getOwner() == 0L ? "(autoplay)" : "(requested by **" + FormatUtil.formatUsername(rm.user) + "**)");
        }
        return already && skippers < required ? Result.fail(msg) : Result.ok(msg);
    }

    public Result forceSkip(Guild g, Member m)
    {
        Result r = guard(g, m, true, true);
        if(r != null) return r;
        AudioHandler h = handler(g);
        String title = h.getPlayer().getPlayingTrack().getInfo().title;
        h.getPlayer().stopTrack();
        return Result.ok("Skipped **" + title + "**");
    }

    public Result skipTo(Guild g, Member m, int position)
    {
        Result r = guard(g, m, true, true);
        if(r != null) return r;
        AudioHandler h = handler(g);
        if(position < 1 || position > h.getQueue().size())
            return Result.fail("Position must be between 1 and " + h.getQueue().size() + "!");
        h.getQueue().skip(position - 1);
        String title = h.getQueue().get(0).getTrack().getInfo().title;
        h.getPlayer().stopTrack();
        return Result.ok("Skipped to **" + title + "**");
    }

    public Result stop(Guild g, Member m)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        AudioHandler h = handler(g);
        h.stopAndClear();
        h.disconnect();
        return Result.ok("The player has stopped and the queue has been cleared.");
    }

    public Result previous(Guild g, Member m)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        return handler(g).playPrevious() ? Result.ok("Playing the previous track.") : Result.fail("There is no previous track.");
    }

    public Result setLoop(Guild g, Member m, RepeatMode mode)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        bot.getSettingsManager().getSettings(g).setRepeatMode(mode);
        bot.getRequestChannels().refresh(g.getIdLong());
        return Result.ok("Repeat mode is now **" + mode.getUserFriendlyName() + "**" + (mode.getEmoji() == null ? "" : " " + mode.getEmoji()));
    }

    public Result cycleLoop(Guild g, Member m)
    {
        RepeatMode cur = bot.getSettingsManager().getSettings(g).getRepeatMode();
        RepeatMode next = cur == RepeatMode.OFF ? RepeatMode.ALL : cur == RepeatMode.ALL ? RepeatMode.SINGLE : RepeatMode.OFF;
        return setLoop(g, m, next);
    }

    /** DJs shuffle the whole queue; everyone else shuffles only the tracks they added. */
    public Result shuffle(Guild g, Member m)
    {
        Result r = guard(g, m, false, false);
        if(r != null) return r;
        AudioHandler h = handler(g);
        if(h.getQueue().size() < 2)
            return Result.fail("There are not enough tracks in the queue to shuffle.");
        if(Access.isDJ(bot, g, m))
        {
            Set<Long> owners = new LinkedHashSet<>();
            for(QueuedTrack qt : h.getQueue().getList())
                owners.add(qt.getIdentifier());
            owners.forEach(o -> h.getQueue().shuffle(o));
            return Result.ok("Shuffled the queue.");
        }
        int count = h.getQueue().shuffle(m.getIdLong());
        return switch(count)
        {
            case 0 -> Result.fail("You don't have any music in the queue to shuffle!");
            case 1 -> Result.fail("You only have one song in the queue!");
            default -> Result.ok("You successfully shuffled your " + count + " entries.");
        };
    }

    public Result setVolume(Guild g, Member m, int volume)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        if(volume < 0 || volume > 150)
            return Result.fail("Volume must be between 0 and 150!");
        AudioHandler h = handler(g);
        int old = h.getPlayer().getVolume();
        h.getPlayer().setVolume(volume);
        bot.getSettingsManager().getSettings(g).setVolume(volume);
        bot.getRequestChannels().refresh(g.getIdLong());
        return Result.ok(FormatUtil.volumeIcon(volume) + " Volume changed from `" + old + "` to `" + volume + "`");
    }

    public Result adjustVolume(Guild g, Member m, int delta)
    {
        int cur = handler(g).getPlayer().getVolume();
        return setVolume(g, m, Math.max(0, Math.min(150, cur + delta)));
    }

    public Result seek(Guild g, Member m, String timeText)
    {
        Result r = guard(g, m, false, true);
        if(r != null) return r;
        AudioTrack t = handler(g).getPlayer().getPlayingTrack();
        if(!t.isSeekable())
            return Result.fail("This track is not seekable.");
        RequestMetadata rm = t.getUserData(RequestMetadata.class);
        if(!Access.isDJ(bot, g, m) && (rm == null || rm.getOwner() != m.getIdLong()))
            return Result.fail("You cannot seek **" + t.getInfo().title + "** because you didn't add it!");
        TimeUtil.SeekTime st = TimeUtil.parseTime(timeText);
        if(st == null)
            return Result.fail("Invalid time! Examples: `1:02:23`, `+1:10`, `-90`, `1h10m`, `+90s`");
        long target = st.relative ? t.getPosition() + st.milliseconds : st.milliseconds;
        target = Math.max(0, target);
        if(target > t.getDuration())
            return Result.fail("Cannot seek to `" + TimeUtil.formatTime(target) + "` because the track is only `" + TimeUtil.formatTime(t.getDuration()) + "` long!");
        try
        {
            t.setPosition(target);
        }
        catch(Exception e)
        {
            return Result.fail("An error occurred while trying to seek: " + e.getMessage());
        }
        return Result.ok("Seeked to `" + TimeUtil.formatTime(t.getPosition()) + "/" + TimeUtil.formatTime(t.getDuration()) + "`");
    }

    public Result remove(Guild g, Member m, int position)
    {
        Result r = guard(g, m, false, false);
        if(r != null) return r;
        AudioHandler h = handler(g);
        if(h.getQueue().isEmpty())
            return Result.fail("There is nothing in the queue!");
        if(position < 1 || position > h.getQueue().size())
            return Result.fail("Position must be between 1 and " + h.getQueue().size() + "!");
        QueuedTrack qt = h.getQueue().get(position - 1);
        boolean own = qt.getIdentifier() == m.getIdLong();
        if(!own && !Access.isDJ(bot, g, m))
            return Result.fail("You cannot remove **" + qt.getTrack().getInfo().title + "** because you didn't add it!");
        h.getQueue().remove(position - 1);
        return Result.ok("Removed **" + qt.getTrack().getInfo().title + "** from the queue");
    }

    public Result move(Guild g, Member m, int from, int to)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        AudioHandler h = handler(g);
        int size = h.getQueue().size();
        if(from < 1 || to < 1 || from > size || to > size)
            return Result.fail("Positions must be between 1 and " + size + "!");
        if(from == to)
            return Result.fail("Can't move a track to the same position.");
        QueuedTrack t = h.getQueue().moveItem(from - 1, to - 1);
        return Result.ok("Moved **" + t.getTrack().getInfo().title + "** from position `" + from + "` to `" + to + "`.");
    }

    public Result clear(Guild g, Member m)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        AudioHandler h = handler(g);
        int n = h.getQueue().size();
        h.getQueue().clear();
        return Result.ok("Cleared **" + n + "** track" + (n == 1 ? "" : "s") + " from the queue.");
    }

    public Result setAutoplay(Guild g, Member m, boolean enabled)
    {
        Result r = guard(g, m, true, false);
        if(r != null) return r;
        bot.getSettingsManager().getSettings(g).setAutoplay(enabled);
        bot.getRequestChannels().refresh(g.getIdLong());
        return Result.ok("Autoplay is now **" + (enabled ? "on" : "off") + "**" + (enabled ? " — related tracks will play when the queue ends." : "."));
    }

    public Result toggleAutoplay(Guild g, Member m)
    {
        return setAutoplay(g, m, !bot.getSettingsManager().getSettings(g).isAutoplay());
    }

    /** 24/7 mode keeps the bot in the voice channel and restores the queue after restarts. Admin only. */
    public Result set247(Guild g, Member m, boolean enabled)
    {
        if(!Access.isAdmin(bot, m))
            return Result.fail("You need the Manage Server permission to change 24/7 mode.");
        Settings s = bot.getSettingsManager().getSettings(g);
        if(enabled)
        {
            AudioChannel vc = m.getVoiceState() == null ? null : m.getVoiceState().getChannel();
            if(vc == null)
                vc = g.getSelfMember().getVoiceState().getChannel();
            if(vc == null)
                return Result.fail("Join a voice channel first so I know where to stay.");
            if(!(vc instanceof net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel v))
                return Result.fail("24/7 mode works in regular voice channels.");
            s.setStay247(true, v.getIdLong());
            handler(g);
            if(!g.getSelfMember().getVoiceState().inAudioChannel())
            {
                g.getAudioManager().openAudioConnection(vc);
                handler(g).onJoined(vc.getIdLong());
            }
            return Result.ok("24/7 mode is **on**. I'll stay in " + vc.getAsMention() + " and come back after restarts.");
        }
        s.setStay247(false, 0);
        return Result.ok("24/7 mode is **off**. I'll leave when the queue ends.");
    }

    /** Position and ETA helper used when announcing queued tracks. */
    public static long etaMs(AudioHandler h, int queuePosition)
    {
        long eta = 0;
        AudioTrack cur = h.getPlayer().getPlayingTrack();
        if(cur != null && !cur.getInfo().isStream)
            eta += Math.max(0, cur.getDuration() - cur.getPosition());
        List<QueuedTrack> list = h.getQueue().getList();
        for(int i = 0; i < queuePosition - 1 && i < list.size(); i++)
            if(!list.get(i).getTrack().getInfo().isStream)
                eta += list.get(i).getTrack().getDuration();
        return eta;
    }
}
