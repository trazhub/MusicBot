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
import com.jagrosh.jmusicbot.settings.Settings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the bot where it should be. If the voice connection drops while music is queued, or the guild is in
 * 24/7 mode, the bot rejoins with exponential backoff. Deliberate leaves (stop, leave, alone timeout) are
 * never undone. A periodic sweep re-joins 24/7 channels that were lost for any reason (restarts, outages).
 */
public class VoiceWatchdog
{
    private static final Logger LOG = LoggerFactory.getLogger("VoiceWatchdog");
    private static final long[] BACKOFF_SECONDS = {3, 10, 30, 60, 120, 300};
    private static final int NON_247_MAX_ATTEMPTS = 3;

    private final Bot bot;
    /** guild id -> reconnect attempts in the current outage (guards against fighting an admin who keeps kicking the bot). */
    private final Map<Long, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastAttemptAt = new ConcurrentHashMap<>();

    public VoiceWatchdog(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        bot.getThreadpool().scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    /** Logs every voice connection state change; this is the first place to look when voice misbehaves. */
    public ConnectionListener listenerFor(long guildId)
    {
        return new ConnectionListener()
        {
            @Override
            public void onStatusChange(ConnectionStatus status)
            {
                switch(status)
                {
                    case CONNECTED -> LOG.info("[{}] voice connected", guildId);
                    case DISCONNECTED_KICKED_FROM_CHANNEL, DISCONNECTED_CHANNEL_DELETED, DISCONNECTED_REMOVED_FROM_GUILD,
                         DISCONNECTED_REMOVED_DURING_RECONNECT, DISCONNECTED_LOST_PERMISSION ->
                            LOG.warn("[{}] voice disconnected: {}", guildId, status);
                    case ERROR_LOST_CONNECTION, ERROR_CANNOT_RESUME, ERROR_UNSUPPORTED_ENCRYPTION_MODES, ERROR_CONNECTION_TIMEOUT,
                         ERROR_WEBSOCKET_UNABLE_TO_CONNECT, ERROR_UDP_UNABLE_TO_CONNECT, DISCONNECTED_AUTHENTICATION_FAILURE ->
                            LOG.error("[{}] voice connection error: {} (if this repeats, see docs/TROUBLESHOOTING.md)", guildId, status);
                    default -> LOG.debug("[{}] voice status {}", guildId, status);
                }
            }

        };
    }

    public void onVoiceUpdate(GuildVoiceUpdateEvent event)
    {
        if(!event.getEntity().getId().equals(event.getJDA().getSelfUser().getId()))
            return;
        Guild guild = event.getGuild();
        AudioChannel joined = event.getChannelJoined();
        AudioChannel left = event.getChannelLeft();
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if(joined != null)
        {
            if(handler != null)
                handler.onJoined(joined.getIdLong());
            attempts.remove(guild.getIdLong());
            return;
        }
        if(left == null || handler == null || handler.isIntentionalLeave())
            return;
        Settings s = bot.getSettingsManager().getSettings(guild);
        boolean wanted = s.isStay247() || handler.getPlayer().getPlayingTrack() != null || !handler.getQueue().isEmpty();
        if(!wanted)
            return;
        LOG.info("[{}] lost voice connection to {}; will try to reconnect", guild.getIdLong(), left.getName());
        schedule(guild.getIdLong(), left.getIdLong());
    }

    private void schedule(long guildId, long channelId)
    {
        int n = attempts.merge(guildId, 1, Integer::sum);
        Guild guild = bot.getJDA().getGuildById(guildId);
        if(guild == null)
            return;
        boolean is247 = bot.getSettingsManager().getSettings(guildId).isStay247();
        if(!is247 && n > NON_247_MAX_ATTEMPTS)
        {
            LOG.info("[{}] giving up reconnecting after {} attempts", guildId, n - 1);
            return;
        }
        long delay = BACKOFF_SECONDS[Math.min(n - 1, BACKOFF_SECONDS.length - 1)];
        lastAttemptAt.put(guildId, System.currentTimeMillis());
        bot.getThreadpool().schedule(() -> reconnect(guildId, channelId), delay, TimeUnit.SECONDS);
    }

    private void reconnect(long guildId, long channelId)
    {
        try
        {
            Guild guild = bot.getJDA().getGuildById(guildId);
            if(guild == null || guild.getSelfMember().getVoiceState().inAudioChannel())
                return;
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if(handler != null && handler.isIntentionalLeave())
                return;
            AudioChannel ch = guild.getChannelById(AudioChannel.class, channelId);
            if(ch == null)
                return;
            LOG.info("[{}] reconnecting to {}", guildId, ch.getName());
            guild.getAudioManager().openAudioConnection(ch);
            // verify shortly; if still not connected, back off further
            bot.getThreadpool().schedule(() ->
            {
                Guild g = bot.getJDA().getGuildById(guildId);
                if(g != null && !g.getSelfMember().getVoiceState().inAudioChannel())
                    schedule(guildId, channelId);
            }, 15, TimeUnit.SECONDS);
        }
        catch(Exception e)
        {
            LOG.warn("[{}] reconnect failed: {}", guildId, e.toString());
            schedule(guildId, channelId);
        }
    }

    /** Re-joins 24/7 channels the bot is not in (after restarts, long outages or exhausted retries). */
    private void sweep()
    {
        try
        {
            attempts.entrySet().removeIf(e -> System.currentTimeMillis() - lastAttemptAt.getOrDefault(e.getKey(), 0L) > 10 * 60_000L);
            for(long guildId : bot.getSettingsManager().knownGuilds())
            {
                Settings s = bot.getSettingsManager().getSettings(guildId);
                if(!s.isStay247() || s.getStayChannelId() == 0)
                    continue;
                Guild guild = bot.getJDA().getGuildById(guildId);
                if(guild == null || guild.getSelfMember().getVoiceState().inAudioChannel())
                    continue;
                AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
                if(handler.isIntentionalLeave())
                    continue;
                if(attempts.getOrDefault(guildId, 0) > 0)
                    continue; // a backoff retry is already in flight
                AudioChannel ch = guild.getChannelById(AudioChannel.class, s.getStayChannelId());
                if(ch != null)
                {
                    LOG.info("[{}] 24/7 sweep rejoining {}", guildId, ch.getName());
                    guild.getAudioManager().openAudioConnection(ch);
                    handler.onJoined(ch.getIdLong());
                }
            }
        }
        catch(Exception e)
        {
            LOG.warn("Voice sweep failed: {}", e.toString());
        }
    }

}
