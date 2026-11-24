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
import com.jagrosh.jmusicbot.service.PlaybackActions;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles the player panel, queue and history buttons. Every click goes through the same checks as the slash commands. */
public class ButtonHandler extends ListenerAdapter
{
    private static final Logger LOG = LoggerFactory.getLogger("Buttons");
    private final Bot bot;
    private final PlaybackActions actions;

    public ButtonHandler(Bot bot)
    {
        this.bot = bot;
        this.actions = bot.getActions();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e)
    {
        String id = e.getComponentId();
        if(!id.startsWith(Controls.PREFIX))
            return;
        try
        {
            handle(e, id);
        }
        catch(Exception ex)
        {
            LOG.error("Button {} failed", id, ex);
            if(!e.isAcknowledged())
                e.reply("Something went wrong.").setEphemeral(true).queue(null, t -> {});
        }
    }

    private void handle(ButtonInteractionEvent e, String id)
    {
        Guild guild = e.getGuild();
        Member member = e.getMember();
        if(guild == null || member == null)
        {
            e.reply("This only works in a server.").setEphemeral(true).queue();
            return;
        }
        if(id.equals(Controls.PREFIX + "noop"))
        {
            e.deferEdit().queue();
            return;
        }
        long wait = bot.getRateLimiter().tryAcquire(guild.getId() + ":" + member.getId());
        if(wait > 0)
        {
            e.reply("Slow down — try again in " + (wait / 1000 + 1) + "s.").setEphemeral(true).queue();
            return;
        }
        bot.getPlayerManager().setUpHandler(guild);

        // paging never changes state
        if(id.startsWith(Controls.QUEUE_PAGE))
        {
            e.editMessage(MessageEditData.fromCreateData(Embeds.queue(bot, guild, page(id, Controls.QUEUE_PAGE)))).queue();
            return;
        }
        if(id.startsWith(Controls.HISTORY_PAGE))
        {
            int page = page(id, Controls.HISTORY_PAGE);
            int total = bot.getHistoryStore().count(guild.getIdLong());
            e.editMessage(MessageEditData.fromCreateData(Embeds.history(
                    bot.getHistoryStore().recent(guild.getIdLong(), Embeds.PAGE_SIZE, page * Embeds.PAGE_SIZE), page, total))).queue();
            return;
        }
        if(id.equals(Controls.QUEUE))
        {
            e.reply(Embeds.queue(bot, guild, 0)).setEphemeral(true).queue();
            return;
        }

        PlaybackActions.Result r = switch(id)
        {
            case Controls.PAUSE -> actions.togglePause(guild, member);
            case Controls.SKIP -> actions.skip(guild, member);
            case Controls.STOP -> actions.stop(guild, member);
            case Controls.PREVIOUS -> actions.previous(guild, member);
            case Controls.LOOP -> actions.cycleLoop(guild, member);
            case Controls.SHUFFLE -> actions.shuffle(guild, member);
            case Controls.VOL_DOWN -> actions.adjustVolume(guild, member, -10);
            case Controls.VOL_UP -> actions.adjustVolume(guild, member, 10);
            case Controls.AUTOPLAY -> actions.toggleAutoplay(guild, member);
            case Controls.QUEUE_CLEAR -> actions.clear(guild, member);
            default -> null;
        };
        if(r == null)
        {
            e.deferEdit().queue();
            return;
        }
        if(!r.ok() || id.equals(Controls.SKIP) || id.equals(Controls.SHUFFLE) || id.equals(Controls.QUEUE_CLEAR))
        {
            // errors and vote/shuffle feedback are private to the clicker
            if(r.ok() && (id.equals(Controls.SHUFFLE) || id.equals(Controls.QUEUE_CLEAR)) && isQueueView(e))
            {
                e.editMessage(MessageEditData.fromCreateData(Embeds.queue(bot, guild, 0))).queue();
                return;
            }
            e.reply(r.message()).setEphemeral(true).queue();
            return;
        }
        // success: refresh the panel the click came from
        if(isQueueView(e))
            e.editMessage(MessageEditData.fromCreateData(Embeds.queue(bot, guild, 0))).queue();
        else
        {
            boolean large = bot.getSettingsManager().getSettings(guild).getRequestMessageId() == e.getMessageIdLong();
            e.editMessage(MessageEditData.fromCreateData(Embeds.nowPlaying(bot, guild, large))).queue();
        }
        if(id.equals(Controls.STOP))
            bot.getRequestChannels().refresh(guild.getIdLong());
    }

    private static boolean isQueueView(ButtonInteractionEvent e)
    {
        var embeds = e.getMessage().getEmbeds();
        return !embeds.isEmpty() && embeds.get(0).getTitle() != null && embeds.get(0).getTitle().startsWith("Queue");
    }

    private static int page(String id, String prefix)
    {
        try
        {
            return Integer.parseInt(id.substring(prefix.length()));
        }
        catch(NumberFormatException ex)
        {
            return 0;
        }
    }
}
