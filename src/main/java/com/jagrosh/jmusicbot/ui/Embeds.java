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
package com.jagrosh.jmusicbot.ui;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.db.HistoryStore;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.time.Instant;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/** Builds every embed and panel the bot shows, so look and feel live in one place. */
public final class Embeds
{
    public static final int PRIMARY = 0x5865F2;
    public static final int PLAYING = 0x5865F2;
    public static final int PAUSED = 0xFEE75C;
    public static final int SUCCESS = 0x57F287;
    public static final int WARNING = 0xFEE75C;
    public static final int ERROR = 0xED4245;
    public static final int PAGE_SIZE = 10;

    private Embeds() {}

    // ---- simple status embeds ----

    public static MessageEmbed ok(String text)
    {
        return new EmbedBuilder().setColor(SUCCESS).setDescription(FormatUtil.filter(text)).build();
    }

    public static MessageEmbed warn(String text)
    {
        return new EmbedBuilder().setColor(WARNING).setDescription(FormatUtil.filter(text)).build();
    }

    public static MessageEmbed error(String text)
    {
        return new EmbedBuilder().setColor(ERROR).setDescription(FormatUtil.filter(text)).build();
    }

    public static MessageEmbed info(String text)
    {
        return new EmbedBuilder().setColor(PRIMARY).setDescription(FormatUtil.filter(text)).build();
    }

    // ---- track helpers ----

    /** Artwork URL for a track: the source's own artwork when provided, else a YouTube thumbnail. */
    public static String artwork(AudioTrack track)
    {
        String art = track.getInfo().artworkUrl;
        if(art != null && art.startsWith("http"))
            return art;
        if(track.getSourceManager() != null && "youtube".equals(track.getSourceManager().getSourceName()))
            return "https://img.youtube.com/vi/" + track.getIdentifier() + "/mqdefault.jpg";
        return null;
    }

    private static String title(AudioTrack track)
    {
        String t = track.getInfo().title == null ? "Unknown title" : track.getInfo().title;
        return t.length() > 250 ? t.substring(0, 249) + "…" : t;
    }

    private static String link(AudioTrack track)
    {
        String uri = track.getInfo().uri;
        return uri != null && uri.startsWith("http") ? uri : null;
    }

    private static String trackLine(AudioTrack track)
    {
        String t = title(track);
        String l = link(track);
        String name = l == null ? "**" + t + "**" : "[**" + t.replace("]", ")").replace("[", "(") + "**](" + l + ")";
        return name;
    }

    // ---- now playing ----

    public static MessageCreateData nowPlaying(Bot bot, Guild guild)
    {
        return nowPlaying(bot, guild, false);
    }

    /** @param large show the artwork as a large image (used by the persistent request-channel panel) */
    public static MessageCreateData nowPlaying(Bot bot, Guild guild, boolean large)
    {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Settings settings = bot.getSettingsManager().getSettings(guild);
        AudioTrack track = handler == null ? null : handler.getPlayer().getPlayingTrack();
        MessageCreateBuilder mb = new MessageCreateBuilder();
        EmbedBuilder eb = new EmbedBuilder();
        if(track == null)
        {
            eb.setColor(PRIMARY).setTitle("Nothing is playing")
                    .setDescription(AudioHandler.STOP_EMOJI + " " + FormatUtil.progressBar(-1) + "\nUse `/play` or type a song name"
                            + (settings.getRequestChannelId() != 0 ? " here" : "") + " to get started.");
            if(handler != null)
                eb.setFooter(footer(settings, handler, 0));
            mb.setEmbeds(eb.build());
            mb.setComponents(Controls.nowPlaying(true, false, settings.getRepeatMode(), settings.isAutoplay()));
            return mb.build();
        }
        boolean paused = handler.getPlayer().isPaused();
        eb.setColor(paused ? PAUSED : PLAYING);
        AudioChannel vc = guild.getSelfMember().getVoiceState() == null ? null : guild.getSelfMember().getVoiceState().getChannel();
        eb.setAuthor((paused ? "Paused" : "Now playing") + (vc == null ? "" : "  •  " + vc.getName()),
                null, guild.getJDA().getSelfUser().getEffectiveAvatarUrl());
        eb.setTitle(title(track), link(track));
        String author = track.getInfo().author;
        StringBuilder desc = new StringBuilder();
        if(author != null && !author.isBlank() && !author.equalsIgnoreCase("unknown artist"))
            desc.append("by **").append(FormatUtil.filter(author)).append("**\n\n");
        if(track.getInfo().isStream)
            desc.append(paused ? AudioHandler.PAUSE_EMOJI : "🔴").append(" **LIVE**  ")
                    .append(FormatUtil.volumeIcon(handler.getPlayer().getVolume())).append(" ").append(handler.getPlayer().getVolume()).append("%");
        else
        {
            double progress = track.getDuration() <= 0 ? 0 : (double) track.getPosition() / track.getDuration();
            desc.append(handler.getStatusEmoji()).append(" ").append(FormatUtil.progressBar(progress))
                    .append("  `").append(TimeUtil.formatTime(track.getPosition())).append(" / ").append(TimeUtil.formatTime(track.getDuration())).append("`  ")
                    .append(FormatUtil.volumeIcon(handler.getPlayer().getVolume())).append(" ").append(handler.getPlayer().getVolume()).append("%");
        }
        eb.setDescription(desc.toString());
        String art = artwork(track);
        if(art != null)
        {
            if(large)
                eb.setImage(art);
            else
                eb.setThumbnail(art);
        }
        RequestMetadata rm = handler.getRequestMetadata();
        eb.addField("Requested by", rm.getOwner() == 0L ? "Autoplay" : "<@" + rm.getOwner() + ">", true);
        List<QueuedTrack> queue = handler.getQueue().getList();
        long total = queue.stream().filter(q -> !q.getTrack().getInfo().isStream).mapToLong(q -> q.getTrack().getDuration()).sum();
        eb.addField("Up next", queue.isEmpty() ? "Nothing queued" : queue.size() + " track" + (queue.size() == 1 ? "" : "s")
                + " • " + TimeUtil.formatTime(total), true);
        if(!queue.isEmpty())
            eb.addField("Next", trackLine(queue.get(0).getTrack()), true);
        eb.setFooter(footer(settings, handler, queue.size()));
        mb.setEmbeds(eb.build());
        mb.setComponents(Controls.nowPlaying(false, paused, settings.getRepeatMode(), settings.isAutoplay()));
        return mb.build();
    }

    private static String footer(Settings s, AudioHandler handler, int queued)
    {
        StringBuilder sb = new StringBuilder();
        if(s.getRepeatMode() != RepeatMode.OFF)
            sb.append(s.getRepeatMode().getEmoji()).append(" ").append(s.getRepeatMode().getUserFriendlyName()).append("  ");
        if(s.isAutoplay())
            sb.append("♾ Autoplay  ");
        if(s.isStay247())
            sb.append("🕒 24/7  ");
        if(sb.isEmpty())
            sb.append("JMusicBot");
        return sb.toString().trim();
    }

    // ---- queue ----

    public static int queuePages(int size)
    {
        return Math.max(1, (int) Math.ceil(size / (double) PAGE_SIZE));
    }

    public static MessageCreateData queue(Bot bot, Guild guild, int page)
    {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        List<QueuedTrack> list = handler == null ? List.of() : handler.getQueue().getList();
        MessageCreateBuilder mb = new MessageCreateBuilder();
        EmbedBuilder eb = new EmbedBuilder().setColor(PRIMARY);
        AudioTrack current = handler == null ? null : handler.getPlayer().getPlayingTrack();
        if(current != null)
            eb.addField("Now playing", trackLine(current) + " `" + TimeUtil.formatTime(current.getDuration()) + "`", false);
        if(list.isEmpty())
        {
            eb.setTitle("Queue");
            eb.setDescription("The queue is empty.");
            return mb.setEmbeds(eb.build()).build();
        }
        int pages = queuePages(list.size());
        page = Math.max(0, Math.min(page, pages - 1));
        StringBuilder sb = new StringBuilder();
        for(int i = page * PAGE_SIZE; i < Math.min(list.size(), (page + 1) * PAGE_SIZE); i++)
        {
            AudioTrack t = list.get(i).getTrack();
            long owner = list.get(i).getRequestMetadata() == null ? 0 : list.get(i).getRequestMetadata().getOwner();
            sb.append("`").append(i + 1).append(".` ").append(trackLine(t)).append(" `")
                    .append(TimeUtil.formatTime(t.getDuration())).append("`")
                    .append(owner == 0 ? "" : " • <@" + owner + ">").append("\n");
        }
        long total = list.stream().filter(q -> !q.getTrack().getInfo().isStream).mapToLong(q -> q.getTrack().getDuration()).sum();
        eb.setTitle("Queue • " + list.size() + " track" + (list.size() == 1 ? "" : "s") + " • " + TimeUtil.formatTime(total));
        eb.setDescription(sb.length() > 4000 ? sb.substring(0, 3990) + "…" : sb.toString());
        Settings s = bot.getSettingsManager().getSettings(guild);
        eb.setFooter("Queue mode: " + s.getQueueType().getUserFriendlyName());
        mb.setEmbeds(eb.build());
        mb.setComponents(Controls.pager(Controls.QUEUE_PAGE, page, pages, true));
        return mb.build();
    }

    // ---- history ----

    public static MessageCreateData history(List<HistoryStore.Entry> entries, int page, int total)
    {
        MessageCreateBuilder mb = new MessageCreateBuilder();
        EmbedBuilder eb = new EmbedBuilder().setColor(PRIMARY).setTitle("Recently played");
        if(entries.isEmpty())
            return mb.setEmbeds(eb.setDescription("Nothing has been played yet.").build()).build();
        StringBuilder sb = new StringBuilder();
        int n = page * PAGE_SIZE;
        for(HistoryStore.Entry e : entries)
        {
            n++;
            String t = e.title().length() > 80 ? e.title().substring(0, 79) + "…" : e.title();
            String name = e.uri() != null && e.uri().startsWith("http")
                    ? "[" + FormatUtil.filter(t).replace("]", ")").replace("[", "(") + "](" + e.uri() + ")" : FormatUtil.filter(t);
            sb.append("`").append(n).append(".` ").append(name)
              .append(e.userId() == 0 ? "" : " • <@" + e.userId() + ">")
              .append(" • <t:").append(e.playedAt() / 1000).append(":R>\n");
        }
        int pages = queuePages(total);
        eb.setDescription(sb.toString()).setFooter("Page " + (page + 1) + " of " + pages).setTimestamp(Instant.now());
        mb.setEmbeds(eb.build());
        if(pages > 1)
            mb.setComponents(Controls.pager(Controls.HISTORY_PAGE, page, pages, false));
        return mb.build();
    }

    // ---- "added to queue" ----

    /** @param position 0 means it starts playing now, otherwise its queue position (1-based). */
    public static MessageEmbed added(AudioTrack track, int position, long etaMs, String requesterMention)
    {
        EmbedBuilder eb = new EmbedBuilder().setColor(SUCCESS)
                .setAuthor(position == 0 ? "Playing now" : "Added to queue")
                .setTitle(title(track), link(track));
        StringBuilder d = new StringBuilder();
        String author = track.getInfo().author;
        if(author != null && !author.isBlank() && !author.equalsIgnoreCase("unknown artist"))
            d.append("by **").append(FormatUtil.filter(author)).append("**\n");
        d.append("`").append(TimeUtil.formatTime(track.getDuration())).append("`");
        if(position > 0)
            d.append(" • position **").append(position).append("**").append(etaMs > 0 ? " • plays in about `" + TimeUtil.formatTime(etaMs) + "`" : "");
        if(requesterMention != null)
            d.append(" • ").append(requesterMention);
        eb.setDescription(d.toString());
        String art = artwork(track);
        if(art != null)
            eb.setThumbnail(art);
        return eb.build();
    }
}
