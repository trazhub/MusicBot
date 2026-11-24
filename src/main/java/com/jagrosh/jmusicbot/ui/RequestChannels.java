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
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.service.Access;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A persistent "request channel": one dedicated text channel holds a live player panel (artwork, progress, buttons)
 * and anything typed there is treated as a song request. The panel message id is stored in SQLite, so it is
 * re-attached after restarts instead of spamming a new panel.
 */
public class RequestChannels
{
    private static final Logger LOG = LoggerFactory.getLogger("RequestChannels");
    private final Bot bot;
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public RequestChannels(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        // keeps the progress bar moving while something plays
        bot.getThreadpool().scheduleWithFixedDelay(() ->
        {
            for(long id : bot.getSettingsManager().knownGuilds())
                if(bot.getSettingsManager().getSettings(id).getRequestChannelId() != 0)
                {
                    Guild g = bot.getJDA().getGuildById(id);
                    if(g != null && g.getAudioManager().getSendingHandler() != null
                            && ((com.jagrosh.jmusicbot.audio.AudioHandler) g.getAudioManager().getSendingHandler()).getPlayer().getPlayingTrack() != null)
                        refreshNow(id);
                }
        }, 20, 20, TimeUnit.SECONDS);
    }

    /** Creates (or moves) the panel in the given channel. */
    public void setup(Guild guild, TextChannel channel, java.util.function.Consumer<String> result)
    {
        Settings s = bot.getSettingsManager().getSettings(guild);
        if(!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
        {
            result.accept("I need View Channel, Send Messages and Embed Links in " + channel.getAsMention() + ".");
            return;
        }
        bot.getPlayerManager().setUpHandler(guild);
        channel.sendMessage(Embeds.nowPlaying(bot, guild, true)).queue(m ->
        {
            // remove the old panel if it lived somewhere else
            deletePanel(guild, s);
            s.setRequestChannel(channel.getIdLong(), m.getIdLong());
            result.accept("Request channel set to " + channel.getAsMention() + ". Type a song name or link there to play it."
                    + (bot.getConfig().useMessageContent() ? ""
                       : "\n⚠ Typing requests needs the **Message Content Intent**: enable it in the Developer Portal and set `messagecontent = true`. "
                         + "Until then, use `/play` and the panel buttons."));
        }, t -> result.accept("I couldn't post in that channel: " + t.getMessage()));
    }

    public void clear(Guild guild)
    {
        Settings s = bot.getSettingsManager().getSettings(guild);
        deletePanel(guild, s);
        s.setRequestChannel(0, 0);
    }

    private void deletePanel(Guild guild, Settings s)
    {
        if(s.getRequestChannelId() == 0 || s.getRequestMessageId() == 0)
            return;
        TextChannel old = guild.getTextChannelById(s.getRequestChannelId());
        if(old != null)
            old.deleteMessageById(s.getRequestMessageId()).queue(null, t -> {});
    }

    /** Debounced panel refresh; safe to call from anywhere, as often as you like. */
    public void refresh(long guildId)
    {
        if(bot.getJDA() == null || bot.getSettingsManager().getSettings(guildId).getRequestChannelId() == 0)
            return;
        pending.compute(guildId, (id, old) ->
        {
            if(old != null && !old.isDone())
                return old;
            return bot.getThreadpool().schedule(() ->
            {
                pending.remove(id);
                refreshNow(id);
            }, 750, TimeUnit.MILLISECONDS);
        });
    }

    private void refreshNow(long guildId)
    {
        try
        {
            Guild guild = bot.getJDA().getGuildById(guildId);
            Settings s = bot.getSettingsManager().getSettings(guildId);
            if(guild == null || s.getRequestChannelId() == 0)
                return;
            TextChannel tc = guild.getTextChannelById(s.getRequestChannelId());
            if(tc == null)
                return;
            MessageCreateData data = Embeds.nowPlaying(bot, guild, true);
            if(s.getRequestMessageId() == 0)
            {
                tc.sendMessage(data).queue(m -> s.setRequestChannel(tc.getIdLong(), m.getIdLong()));
                return;
            }
            tc.editMessageById(s.getRequestMessageId(), MessageEditData.fromCreateData(data)).queue(null, t ->
            {
                // the panel was deleted: post a fresh one
                tc.sendMessage(data).queue(m -> s.setRequestChannel(tc.getIdLong(), m.getIdLong()), x -> {});
            });
        }
        catch(Exception e)
        {
            LOG.debug("Panel refresh failed for {}: {}", guildId, e.toString());
        }
    }

    public void onMessageDelete(MessageDeleteEvent event)
    {
        if(!event.isFromGuild())
            return;
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if(s.getRequestMessageId() == event.getMessageIdLong())
            refresh(event.getGuild().getIdLong());
    }

    /** Treats a message in the request channel as a song request. */
    public void onMessage(MessageReceivedEvent event)
    {
        if(!event.isFromGuild() || event.getAuthor().isBot() || event.getMember() == null)
            return;
        Guild guild = event.getGuild();
        Settings s = bot.getSettingsManager().getSettings(guild);
        if(s.getRequestChannelId() == 0 || event.getChannel().getIdLong() != s.getRequestChannelId())
            return;
        String content = event.getMessage().getContentRaw().trim();
        if(content.isEmpty() && !event.getMessage().getAttachments().isEmpty())
            content = event.getMessage().getAttachments().get(0).getUrl();
        if(content.isEmpty())
            return;
        String query = content;
        TextChannel tc = event.getChannel().asTextChannel();
        deleteLater(event.getMessage());
        long wait = bot.getRateLimiter().tryAcquire(guild.getId() + ":" + event.getAuthor().getId());
        if(wait > 0)
        {
            temp(tc, Embeds.warn("<@" + event.getAuthor().getId() + "> slow down — try again in " + (wait / 1000 + 1) + "s."));
            return;
        }
        String err = Access.checkListening(bot, guild, event.getMember(), true);
        if(err != null)
        {
            temp(tc, Embeds.error("<@" + event.getAuthor().getId() + "> " + err));
            return;
        }
        bot.getLoader().load(guild, event.getMember(), query, false, o -> temp(tc, o.embed()));
    }

    private void deleteLater(Message m)
    {
        try
        {
            if(m.getGuild().getSelfMember().hasPermission(m.getGuildChannel(), Permission.MESSAGE_MANAGE))
                m.delete().queueAfter(2, TimeUnit.SECONDS, null, t -> {});
        }
        catch(Exception ignored) { /* best effort */ }
    }

    private void temp(TextChannel tc, MessageEmbed embed)
    {
        if(tc.canTalk())
            tc.sendMessageEmbeds(embed).queue(m -> m.delete().queueAfter(12, TimeUnit.SECONDS, null, t -> {}), t -> {});
    }
}
