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
import com.jagrosh.jmusicbot.db.QueueStore;
import com.jagrosh.jmusicbot.settings.Settings;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodically snapshots every active player to SQLite and restores them after a restart. */
public class PersistenceService
{
    private static final Logger LOG = LoggerFactory.getLogger("Persistence");
    private static final long MAX_RESUME_AGE_MS = 30 * 60_000L;
    private static final long FORCE_SAVE_MS = 30_000L;

    private final Bot bot;
    private final Map<Long, String> signatures = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastSaved = new ConcurrentHashMap<>();

    public PersistenceService(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        bot.getThreadpool().scheduleWithFixedDelay(this::flushNow, 10, 10, TimeUnit.SECONDS);
    }

    /** Snapshots all active players immediately (also called on shutdown). */
    public void flushNow()
    {
        try
        {
            for(Guild guild : bot.getJDA().getGuilds())
            {
                if(!(guild.getAudioManager().getSendingHandler() instanceof AudioHandler handler))
                    continue;
                AudioChannel vc = guild.getAudioManager().getConnectedChannel();
                long vcId = vc != null ? vc.getIdLong() : handler.getLastVoiceChannelId();
                QueueStore.State state = handler.snapshot(vcId);
                long id = guild.getIdLong();
                if(state == null)
                {
                    if(signatures.remove(id) != null)
                        bot.getQueueStore().clear(id);
                    continue;
                }
                String sig = signature(state);
                long now = System.currentTimeMillis();
                if(sig.equals(signatures.get(id)) && now - lastSaved.getOrDefault(id, 0L) < FORCE_SAVE_MS)
                    continue;
                signatures.put(id, sig);
                lastSaved.put(id, now);
                bot.getQueueStore().save(state);
            }
        }
        catch(Exception e)
        {
            LOG.warn("Queue snapshot failed: {}", e.toString());
        }
    }

    private static String signature(QueueStore.State s)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(s.voiceChannelId()).append('|').append(s.paused()).append('|');
        if(s.current() != null)
            sb.append(s.current().data().hashCode());
        for(QueueStore.SavedTrack t : s.queue())
            sb.append(',').append(t.data().hashCode());
        return sb.toString();
    }

    /** Restores players that were active when the bot last stopped (24/7 guilds always, others if recent). */
    public void restoreAll()
    {
        List<Long> ids = bot.getQueueStore().activeGuilds();
        for(long id : ids)
        {
            try
            {
                Guild guild = bot.getJDA().getGuildById(id);
                if(guild == null)
                    continue;
                Settings s = bot.getSettingsManager().getSettings(guild);
                QueueStore.State state = bot.getQueueStore().load(id);
                if(state == null || !state.active())
                    continue;
                boolean fresh = System.currentTimeMillis() - state.updatedAt() < MAX_RESUME_AGE_MS;
                if(!s.isStay247() && !fresh)
                {
                    bot.getQueueStore().clear(id);
                    continue;
                }
                AudioChannel vc = guild.getChannelById(AudioChannel.class, state.voiceChannelId());
                if(vc == null)
                    vc = guild.getChannelById(AudioChannel.class, s.getStayChannelId());
                if(vc == null)
                    vc = s.getVoiceChannel(guild);
                if(vc == null)
                    continue;
                AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
                int restored = handler.restore(state);
                guild.getAudioManager().openAudioConnection(vc);
                handler.onJoined(vc.getIdLong());
                LOG.info("[{}] restored {} track(s) in {}", id, restored, vc.getName());
            }
            catch(Exception e)
            {
                LOG.warn("[{}] could not restore player: {}", id, e.toString());
            }
        }
    }
}
