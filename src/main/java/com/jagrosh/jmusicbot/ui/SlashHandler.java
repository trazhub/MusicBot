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

import com.jagrosh.jlyrics.LyricsClient;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.service.Access;
import com.jagrosh.jmusicbot.service.PlaybackActions;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Slash commands: every playback control, settings and help. */
public class SlashHandler extends ListenerAdapter
{
    private static final Logger LOG = LoggerFactory.getLogger("Slash");
    private static final Set<String> NO_CHANNEL_RESTRICTION = Set.of("help", "ping", "settings", "config");
    private static final String SELECT_ID = Controls.PREFIX + "pick";

    private final Bot bot;
    private final PlaybackActions actions;
    private final LyricsClient lyrics = new LyricsClient();

    public SlashHandler(Bot bot)
    {
        this.bot = bot;
        this.actions = bot.getActions();
    }

    // ------------------------------------------------------------------ definitions

    public static List<SlashCommandData> definitions()
    {
        List<SlashCommandData> c = new ArrayList<>();
        c.add(Commands.slash("play", "Play a song, playlist or stream (YouTube, SoundCloud, Spotify links, URLs)")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song name or link", true).setAutoComplete(true).setMaxLength(500),
                        new OptionData(OptionType.BOOLEAN, "next", "Play it next instead of at the end of the queue")));
        c.add(Commands.slash("search", "Search YouTube and pick a result")
                .addOption(OptionType.STRING, "query", "What to search for", true));
        c.add(Commands.slash("radio", "Tune in to an internet radio station by name or stream URL")
                .addOption(OptionType.STRING, "station", "Station name or stream URL", true));
        c.add(Commands.slash("playlist", "Play one of the server's saved playlists")
                .addOptions(new OptionData(OptionType.STRING, "name", "Playlist name", true).setAutoComplete(true)));
        c.add(Commands.slash("pause", "Pause playback"));
        c.add(Commands.slash("resume", "Resume playback"));
        c.add(Commands.slash("skip", "Vote to skip the current track"));
        c.add(Commands.slash("forceskip", "Skip the current track immediately (DJ)"));
        c.add(Commands.slash("skipto", "Skip to a position in the queue (DJ)")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "Queue position", true).setMinValue(1)));
        c.add(Commands.slash("previous", "Play the previous track (DJ)"));
        c.add(Commands.slash("stop", "Stop playback, clear the queue and leave (DJ)"));
        c.add(Commands.slash("queue", "Show the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "page", "Page number").setMinValue(1)));
        c.add(Commands.slash("nowplaying", "Show the player panel with controls"));
        c.add(Commands.slash("remove", "Remove a track from the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "Queue position", true).setMinValue(1)));
        c.add(Commands.slash("move", "Move a track in the queue (DJ)")
                .addOptions(new OptionData(OptionType.INTEGER, "from", "Current position", true).setMinValue(1),
                        new OptionData(OptionType.INTEGER, "to", "New position", true).setMinValue(1)));
        c.add(Commands.slash("clear", "Clear the queue (DJ)"));
        c.add(Commands.slash("shuffle", "Shuffle the queue (everyone shuffles their own tracks; DJs shuffle all)"));
        c.add(Commands.slash("loop", "Set repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", true)
                        .addChoice("Off", "off").addChoice("Current track", "single").addChoice("Whole queue", "all")));
        c.add(Commands.slash("seek", "Seek in the current track, e.g. 1:30, +30, -10, 1m20s")
                .addOption(OptionType.STRING, "time", "Target time", true));
        c.add(Commands.slash("volume", "Show or set the volume (0-150)")
                .addOptions(new OptionData(OptionType.INTEGER, "level", "Volume").setRequiredRange(0, 150)));
        c.add(Commands.slash("autoplay", "Keep playing related tracks when the queue ends (DJ)")
                .addOption(OptionType.BOOLEAN, "enabled", "Turn autoplay on or off", true));
        c.add(Commands.slash("247", "Stay in the voice channel 24/7 and restore the queue after restarts (admin)")
                .addOption(OptionType.BOOLEAN, "enabled", "Turn 24/7 mode on or off", true));
        c.add(Commands.slash("history", "Show recently played tracks"));
        c.add(Commands.slash("lyrics", "Show lyrics for the current or a given song")
                .addOption(OptionType.STRING, "song", "Song name (defaults to the current track)"));
        c.add(Commands.slash("settings", "Show this server's music settings"));
        c.add(Commands.slash("help", "Show all commands"));
        c.add(Commands.slash("ping", "Check the bot's latency"));
        c.add(Commands.slash("config", "Configure the bot for this server (admin)")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("dj", "Set or clear the DJ role").addOption(OptionType.ROLE, "role", "DJ role (omit to clear)"),
                        new SubcommandData("textchannel", "Restrict music commands to a text channel")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Text channel (omit for any)").setChannelTypes(ChannelType.TEXT)),
                        new SubcommandData("voicechannel", "Restrict music to a voice channel")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Voice channel (omit for any)").setChannelTypes(ChannelType.VOICE)),
                        new SubcommandData("queuetype", "Set the queue type")
                                .addOptions(new OptionData(OptionType.STRING, "type", "Queue type", true)
                                        .addChoice("Fair (round-robin between users)", "FAIR").addChoice("Linear (first come first served)", "LINEAR")),
                        new SubcommandData("skipratio", "Set the share of listeners that must vote to skip (0-1)")
                                .addOptions(new OptionData(OptionType.NUMBER, "ratio", "e.g. 0.55", true).setRequiredRange(0, 1)),
                        new SubcommandData("requestchannel", "Create the persistent player panel in a channel (omit to remove it)")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Channel for the panel").setChannelTypes(ChannelType.TEXT))));
        return c;
    }

    public void register(JDA jda)
    {
        long guildId = bot.getConfig().getSlashGuild();
        List<SlashCommandData> defs = definitions();
        if(guildId != 0)
        {
            Guild g = jda.getGuildById(guildId);
            if(g == null)
            {
                LOG.warn("slashguild {} is not a guild this bot is in; registering globally instead", guildId);
                jda.updateCommands().addCommands(defs).queue(ok -> LOG.info("Registered {} global slash commands", ok.size()),
                        t -> LOG.error("Slash registration failed: {}", t.toString()));
                return;
            }
            g.updateCommands().addCommands(defs).queue(ok -> LOG.info("Registered {} slash commands in guild {}", ok.size(), guildId),
                    t -> LOG.error("Slash registration failed: {}", t.toString()));
        }
        else
            jda.updateCommands().addCommands(defs).queue(ok -> LOG.info("Registered {} global slash commands", ok.size()),
                    t -> LOG.error("Slash registration failed: {}", t.toString()));
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e)
    {
        try
        {
            handle(e);
        }
        catch(Exception ex)
        {
            LOG.error("Slash command /{} failed", e.getName(), ex);
            if(e.isAcknowledged())
                e.getHook().sendMessageEmbeds(Embeds.error("Something went wrong running that command.")).setEphemeral(true).queue(null, t -> {});
            else
                e.replyEmbeds(Embeds.error("Something went wrong running that command.")).setEphemeral(true).queue(null, t -> {});
        }
    }

    private void handle(SlashCommandInteractionEvent e)
    {
        if(!e.isFromGuild() || e.getGuild() == null || e.getMember() == null)
        {
            e.replyEmbeds(Embeds.error("This command only works in a server.")).setEphemeral(true).queue();
            return;
        }
        Guild guild = e.getGuild();
        Member member = e.getMember();
        long wait = bot.getRateLimiter().tryAcquire(guild.getId() + ":" + member.getId());
        if(wait > 0)
        {
            e.replyEmbeds(Embeds.warn("You're going too fast — try again in " + (wait / 1000 + 1) + "s.")).setEphemeral(true).queue();
            return;
        }
        String name = e.getName();
        if(!NO_CHANNEL_RESTRICTION.contains(name))
        {
            String err = Access.checkTextChannel(bot, guild, e.getChannel());
            if(err != null)
            {
                e.replyEmbeds(Embeds.error(err)).setEphemeral(true).queue();
                return;
            }
        }
        bot.getPlayerManager().setUpHandler(guild).setAnnounceChannel(e.getChannel().getIdLong());
        switch(name)
        {
            case "play" -> play(e, guild, member);
            case "search" -> search(e, guild, member);
            case "radio" -> radio(e, guild, member);
            case "playlist" -> playlist(e, guild, member);
            case "pause" -> reply(e, actions.setPaused(guild, member, true));
            case "resume" -> reply(e, actions.setPaused(guild, member, false));
            case "skip" -> reply(e, actions.skip(guild, member));
            case "forceskip" -> reply(e, actions.forceSkip(guild, member));
            case "skipto" -> reply(e, actions.skipTo(guild, member, (int) e.getOption("position").getAsLong()));
            case "previous" -> reply(e, actions.previous(guild, member));
            case "stop" -> reply(e, actions.stop(guild, member));
            case "queue" -> e.reply(Embeds.queue(bot, guild, Math.toIntExact(e.getOption("page", 1L, o -> o.getAsLong())) - 1)).queue();
            case "nowplaying" -> e.reply(Embeds.nowPlaying(bot, guild)).queue(h -> h.retrieveOriginal().queue(m -> bot.getNowplayingHandler().setLastNPMessage(m)));
            case "remove" -> reply(e, actions.remove(guild, member, (int) e.getOption("position").getAsLong()));
            case "move" -> reply(e, actions.move(guild, member, (int) e.getOption("from").getAsLong(), (int) e.getOption("to").getAsLong()));
            case "clear" -> reply(e, actions.clear(guild, member));
            case "shuffle" -> reply(e, actions.shuffle(guild, member));
            case "loop" -> reply(e, actions.setLoop(guild, member, parseLoop(e.getOption("mode").getAsString())));
            case "seek" -> reply(e, actions.seek(guild, member, e.getOption("time").getAsString()));
            case "volume" -> volume(e, guild, member);
            case "autoplay" -> reply(e, actions.setAutoplay(guild, member, e.getOption("enabled").getAsBoolean()));
            case "247" -> reply(e, actions.set247(guild, member, e.getOption("enabled").getAsBoolean()));
            case "history" -> history(e, guild);
            case "lyrics" -> lyrics(e, guild);
            case "settings" -> e.replyEmbeds(settingsEmbed(guild)).queue();
            case "help" -> e.replyEmbeds(helpEmbed()).setEphemeral(true).queue();
            case "ping" -> e.replyEmbeds(Embeds.info("🏓 Gateway ping: **" + e.getJDA().getGatewayPing() + " ms**")).setEphemeral(true).queue();
            case "config" -> config(e, guild, member);
            default -> e.replyEmbeds(Embeds.error("Unknown command.")).setEphemeral(true).queue();
        }
    }

    private static RepeatMode parseLoop(String s)
    {
        return switch(s.toLowerCase(Locale.ROOT))
        {
            case "single", "one" -> RepeatMode.SINGLE;
            case "all" -> RepeatMode.ALL;
            default -> RepeatMode.OFF;
        };
    }

    private void reply(SlashCommandInteractionEvent e, PlaybackActions.Result r)
    {
        e.replyEmbeds(r.ok() ? Embeds.ok(r.message()) : Embeds.error(r.message())).setEphemeral(!r.ok()).queue();
    }

    // ------------------------------------------------------------------ playback

    private void play(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        String err = Access.checkListening(bot, guild, member, true);
        if(err != null)
        {
            e.replyEmbeds(Embeds.error(err)).setEphemeral(true).queue();
            return;
        }
        boolean next = e.getOption("next", false, o -> o.getAsBoolean());
        if(next && !Access.isDJ(bot, guild, member))
        {
            e.replyEmbeds(Embeds.error("Only DJs can queue a track to play next.")).setEphemeral(true).queue();
            return;
        }
        e.deferReply().queue(hook -> bot.getLoader().load(guild, member, e.getOption("query").getAsString(), next, o ->
                hook.editOriginalEmbeds(o.embed()).queue(null, t -> {})));
    }

    private void radio(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        String err = Access.checkListening(bot, guild, member, true);
        if(err != null)
        {
            e.replyEmbeds(Embeds.error(err)).setEphemeral(true).queue();
            return;
        }
        e.deferReply().queue(hook -> bot.getLoader().loadRadio(guild, member, e.getOption("station").getAsString(), o ->
                hook.editOriginalEmbeds(o.embed()).queue(null, t -> {})));
    }

    private void search(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        String err = Access.checkListening(bot, guild, member, false);
        if(err != null)
        {
            e.replyEmbeds(Embeds.error(err)).setEphemeral(true).queue();
            return;
        }
        String q = e.getOption("query").getAsString();
        e.deferReply().queue(hook -> bot.getPlayerManager().loadItemOrdered(guild.getIdLong(), "ytsearch:" + q, new AudioLoadResultHandler()
        {
            @Override public void trackLoaded(AudioTrack t) { showResults(hook, List.of(t), member, q); }
            @Override public void playlistLoaded(AudioPlaylist p) { showResults(hook, p.getTracks().stream().limit(5).collect(Collectors.toList()), member, q); }
            @Override public void noMatches() { hook.editOriginalEmbeds(Embeds.warn("No results for `" + FormatUtil.filter(q) + "`.")).queue(); }
            @Override public void loadFailed(FriendlyException ex) { hook.editOriginalEmbeds(Embeds.error("Search failed: " + FormatUtil.filter(String.valueOf(ex.getMessage())))).queue(); }
        }));
    }

    private void showResults(InteractionHook hook, List<AudioTrack> tracks, Member member, String q)
    {
        StringSelectMenu.Builder menu = StringSelectMenu.create(SELECT_ID + ":" + member.getId()).setPlaceholder("Choose a track to queue");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for(AudioTrack t : tracks)
        {
            i++;
            String title = t.getInfo().title.length() > 90 ? t.getInfo().title.substring(0, 89) + "…" : t.getInfo().title;
            sb.append("`").append(i).append(".` [").append(FormatUtil.filter(title).replace("]", ")")).append("](").append(t.getInfo().uri).append(") `")
                    .append(com.jagrosh.jmusicbot.utils.TimeUtil.formatTime(t.getDuration())).append("`\n");
            menu.addOption(i + ". " + title, t.getInfo().uri, t.getInfo().author == null ? null
                    : (t.getInfo().author.length() > 100 ? t.getInfo().author.substring(0, 99) + "…" : t.getInfo().author));
        }
        hook.editOriginalEmbeds(new EmbedBuilder().setColor(Embeds.PRIMARY).setTitle("Results for \"" + (q.length() > 60 ? q.substring(0, 59) + "…" : q) + "\"")
                .setDescription(sb.toString()).build()).setComponents(ActionRow.of(menu.build())).queue();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent e)
    {
        if(!e.getComponentId().startsWith(SELECT_ID) || e.getGuild() == null || e.getMember() == null)
            return;
        if(!e.getComponentId().endsWith(":" + e.getUser().getId()))
        {
            e.reply("Only the person who searched can pick a result.").setEphemeral(true).queue();
            return;
        }
        Guild guild = e.getGuild();
        String err = Access.checkListening(bot, guild, e.getMember(), true);
        if(err != null)
        {
            e.reply(err).setEphemeral(true).queue();
            return;
        }
        e.deferEdit().queue(hook -> bot.getLoader().load(guild, e.getMember(), e.getValues().get(0), false, o ->
                hook.editOriginalEmbeds(o.embed()).setComponents().queue(null, t -> {})));
    }

    private void playlist(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        String err = Access.checkListening(bot, guild, member, true);
        if(err != null)
        {
            e.replyEmbeds(Embeds.error(err)).setEphemeral(true).queue();
            return;
        }
        String name = e.getOption("name").getAsString();
        PlaylistLoader.Playlist pl = bot.getPlaylistLoader().getPlaylist(name);
        if(pl == null)
        {
            e.replyEmbeds(Embeds.error("I could not find the playlist `" + FormatUtil.filter(name) + "`.")).setEphemeral(true).queue();
            return;
        }
        e.deferReply().queue(hook ->
        {
            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
            pl.loadTracks(bot.getPlayerManager(), at -> handler.addTrack(new QueuedTrack(at, RequestMetadata.fromUser(member.getUser(), name, at))), () ->
            {
                StringBuilder sb = new StringBuilder(pl.getTracks().isEmpty() ? "No tracks were loaded!" : "Loaded **" + pl.getTracks().size() + "** tracks from **" + FormatUtil.filter(name) + "**!");
                if(!pl.getErrors().isEmpty())
                    sb.append("\n").append(pl.getErrors().size()).append(" item(s) failed to load.");
                hook.editOriginalEmbeds(pl.getTracks().isEmpty() ? Embeds.warn(sb.toString()) : Embeds.ok(sb.toString())).queue();
            });
        });
    }

    private void volume(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        if(e.getOption("level") == null)
        {
            int v = bot.getPlayerManager().setUpHandler(guild).getPlayer().getVolume();
            e.replyEmbeds(Embeds.info(FormatUtil.volumeIcon(v) + " Current volume is `" + v + "`")).queue();
            return;
        }
        reply(e, actions.setVolume(guild, member, (int) e.getOption("level").getAsLong()));
    }

    private void history(SlashCommandInteractionEvent e, Guild guild)
    {
        int total = bot.getHistoryStore().count(guild.getIdLong());
        e.reply(Embeds.history(bot.getHistoryStore().recent(guild.getIdLong(), Embeds.PAGE_SIZE, 0), 0, total)).queue();
    }

    private void lyrics(SlashCommandInteractionEvent e, Guild guild)
    {
        String title;
        if(e.getOption("song") != null)
            title = e.getOption("song").getAsString();
        else
        {
            AudioHandler h = bot.getPlayerManager().setUpHandler(guild);
            if(!h.isMusicPlaying(e.getJDA()))
            {
                e.replyEmbeds(Embeds.error("There must be music playing, or give me a song name.")).setEphemeral(true).queue();
                return;
            }
            title = h.getPlayer().getPlayingTrack().getInfo().title;
        }
        e.deferReply().queue(hook -> lyrics.getLyrics(title).thenAccept(l ->
        {
            if(l == null)
            {
                hook.editOriginalEmbeds(Embeds.error("Lyrics for `" + FormatUtil.filter(title) + "` could not be found.")).queue();
                return;
            }
            String content = l.getContent().trim();
            if(content.length() > 15000)
            {
                hook.editOriginalEmbeds(Embeds.warn("Lyrics found but probably not correct: " + l.getURL())).queue();
                return;
            }
            EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.PRIMARY).setAuthor(l.getAuthor()).setTitle(l.getTitle(), l.getURL())
                    .setDescription(content.length() > 4000 ? content.substring(0, 3990) + "…" : content);
            hook.editOriginalEmbeds(eb.build()).queue();
        }).exceptionally(t ->
        {
            hook.editOriginalEmbeds(Embeds.error("Couldn't fetch lyrics right now.")).queue();
            return null;
        }));
    }

    // ------------------------------------------------------------------ settings / admin

    private void config(SlashCommandInteractionEvent e, Guild guild, Member member)
    {
        if(!Access.isAdmin(bot, member))
        {
            e.replyEmbeds(Embeds.error("You need the Manage Server permission to do that.")).setEphemeral(true).queue();
            return;
        }
        Settings s = bot.getSettingsManager().getSettings(guild);
        String sub = e.getSubcommandName() == null ? "" : e.getSubcommandName();
        switch(sub)
        {
            case "dj" ->
            {
                Role role = e.getOption("role") == null ? null : e.getOption("role").getAsRole();
                s.setDJRole(role);
                e.replyEmbeds(Embeds.ok(role == null ? "DJ role cleared. Only members with Manage Server can use DJ commands."
                        : "DJ commands can now be used by members with " + role.getAsMention() + ".")).queue();
            }
            case "textchannel" ->
            {
                TextChannel tc = e.getOption("channel") == null ? null : e.getOption("channel").getAsChannel().asTextChannel();
                s.setTextChannel(tc);
                e.replyEmbeds(Embeds.ok(tc == null ? "Music commands can now be used in any channel." : "Music commands can now only be used in " + tc.getAsMention() + ".")).queue();
            }
            case "voicechannel" ->
            {
                VoiceChannel vc = e.getOption("channel") == null ? null : e.getOption("channel").getAsChannel().asVoiceChannel();
                s.setVoiceChannel(vc);
                e.replyEmbeds(Embeds.ok(vc == null ? "Music can now be played in any voice channel." : "Music can now only be played in " + vc.getAsMention() + ".")).queue();
            }
            case "queuetype" ->
            {
                QueueType t = QueueType.valueOf(e.getOption("type").getAsString());
                s.setQueueType(t);
                AudioHandler h = (AudioHandler) guild.getAudioManager().getSendingHandler();
                if(h != null)
                    h.setQueueType(t);
                e.replyEmbeds(Embeds.ok("Queue type is now **" + t.getUserFriendlyName() + "**.")).queue();
            }
            case "skipratio" ->
            {
                double r = e.getOption("ratio").getAsDouble();
                s.setSkipRatio(r);
                e.replyEmbeds(Embeds.ok("Skip ratio set to **" + Math.round(r * 100) + "%** of listeners.")).queue();
            }
            case "requestchannel" ->
            {
                if(e.getOption("channel") == null)
                {
                    bot.getRequestChannels().clear(guild);
                    e.replyEmbeds(Embeds.ok("Request channel removed.")).queue();
                }
                else
                {
                    TextChannel tc = e.getOption("channel").getAsChannel().asTextChannel();
                    e.deferReply().queue(hook -> bot.getRequestChannels().setup(guild, tc, msg -> hook.editOriginalEmbeds(Embeds.info(msg)).queue()));
                }
            }
            default -> e.replyEmbeds(Embeds.error("Unknown option.")).setEphemeral(true).queue();
        }
    }

    private MessageEmbed settingsEmbed(Guild guild)
    {
        Settings s = bot.getSettingsManager().getSettings(guild);
        TextChannel tc = s.getTextChannel(guild);
        VoiceChannel vc = s.getVoiceChannel(guild);
        Role role = s.getRole(guild);
        double ratio = s.getSkipRatio() == -1 ? bot.getConfig().getSkipRatio() : s.getSkipRatio();
        return new EmbedBuilder().setColor(Embeds.PRIMARY).setTitle("Music settings")
                .addField("Text channel", tc == null ? "Any" : tc.getAsMention(), true)
                .addField("Voice channel", vc == null ? "Any" : vc.getAsMention(), true)
                .addField("DJ role", role == null ? "None (Manage Server)" : role.getAsMention(), true)
                .addField("Repeat", s.getRepeatMode().getUserFriendlyName(), true)
                .addField("Queue type", s.getQueueType().getUserFriendlyName(), true)
                .addField("Skip ratio", Math.round(ratio * 100) + "%", true)
                .addField("Autoplay", s.isAutoplay() ? "On" : "Off", true)
                .addField("24/7 mode", s.isStay247() ? "On" : "Off", true)
                .addField("Request channel", s.getRequestChannelId() == 0 ? "None" : "<#" + s.getRequestChannelId() + ">", true)
                .addField("Default playlist", s.getDefaultPlaylist() == null ? "None" : "**" + FormatUtil.filter(s.getDefaultPlaylist()) + "**", true)
                .setFooter(bot.getJDA().getGuilds().size() + " servers")
                .build();
    }

    private MessageEmbed helpEmbed()
    {
        return new EmbedBuilder().setColor(Embeds.PRIMARY).setTitle("JMusicBot commands")
                .setDescription("Everyone can use the player panel buttons too. Commands marked **DJ** need the DJ role or Manage Server.")
                .addField("🎶 Play", "`/play` `/search` `/radio` `/playlist` `/nowplaying`", false)
                .addField("⏯ Controls", "`/pause` `/resume` `/skip` `/previous` **DJ** `/forceskip` **DJ** `/skipto` **DJ** `/stop` **DJ**\n"
                        + "`/seek` `/volume` **DJ** `/loop` **DJ** `/autoplay` **DJ**", false)
                .addField("📜 Queue", "`/queue` `/remove` `/move` **DJ** `/clear` **DJ** `/shuffle` `/history` `/lyrics`", false)
                .addField("⚙ Server", "`/settings` `/247` **admin** `/config` **admin** (dj, textchannel, voicechannel, queuetype, skipratio, requestchannel)", false)
                .addField("Links", "YouTube, SoundCloud, Bandcamp, Twitch, Vimeo, direct audio URLs and internet radio. "
                        + "**Spotify** links are supported for song info only; the audio always comes from the matching YouTube result.", false)
                .build();
    }

    // ------------------------------------------------------------------ autocomplete

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e)
    {
        if(e.getGuild() == null)
            return;
        try
        {
            String typed = e.getFocusedOption().getValue();
            if(e.getName().equals("play"))
            {
                List<String> hits = bot.getHistoryStore().suggest(e.getGuild().getIdLong(), typed, 10);
                e.replyChoiceStrings(hits.stream().map(t -> t.length() > 100 ? t.substring(0, 99) + "…" : t).distinct().collect(Collectors.toList())).queue();
            }
            else if(e.getName().equals("playlist"))
            {
                String f = typed.toLowerCase(Locale.ROOT);
                e.replyChoiceStrings(bot.getPlaylistLoader().getPlaylistNames().stream()
                        .filter(n -> n.toLowerCase(Locale.ROOT).contains(f)).limit(25).collect(Collectors.toList())).queue();
            }
        }
        catch(Exception ex)
        {
            LOG.debug("Autocomplete failed: {}", ex.toString());
        }
    }
}
