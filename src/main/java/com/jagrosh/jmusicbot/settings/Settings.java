/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.settings;

import com.jagrosh.jdautilities.command.GuildSettingsProvider;
import java.util.Collection;
import java.util.Collections;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;

/**
 * Per-guild settings. Instances are owned by {@link SettingsManager}, which persists every change to SQLite.
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Settings implements GuildSettingsProvider
{
    private final SettingsManager manager;
    private final long guildId;
    protected long textId;
    protected long voiceId;
    protected long roleId;
    private int volume;
    private String defaultPlaylist;
    private RepeatMode repeatMode;
    private QueueType queueType;
    private String prefix;
    private double skipRatio;
    private boolean autoplay;
    private boolean stay247;
    private long stayChannelId;
    private long requestChannelId;
    private long requestMessageId;

    public Settings(SettingsManager manager, long guildId)
    {
        this.manager = manager;
        this.guildId = guildId;
        this.volume = 100;
        this.repeatMode = RepeatMode.OFF;
        this.queueType = QueueType.FAIR;
        this.skipRatio = -1;
    }

    /** Populates every field without triggering a save; used when loading from storage. */
    void load(long textId, long voiceId, long roleId, int volume, String defaultPlaylist, RepeatMode repeatMode,
            String prefix, double skipRatio, QueueType queueType, boolean autoplay, boolean stay247, long stayChannelId,
            long requestChannelId, long requestMessageId)
    {
        this.textId = textId;
        this.voiceId = voiceId;
        this.roleId = roleId;
        this.volume = volume;
        this.defaultPlaylist = defaultPlaylist;
        this.repeatMode = repeatMode;
        this.prefix = prefix;
        this.skipRatio = skipRatio;
        this.queueType = queueType;
        this.autoplay = autoplay;
        this.stay247 = stay247;
        this.stayChannelId = stayChannelId;
        this.requestChannelId = requestChannelId;
        this.requestMessageId = requestMessageId;
    }

    private void save()
    {
        manager.save(this);
    }

    // Getters
    public long getGuildId()
    {
        return guildId;
    }

    public TextChannel getTextChannel(Guild guild)
    {
        return guild == null ? null : guild.getTextChannelById(textId);
    }

    public VoiceChannel getVoiceChannel(Guild guild)
    {
        return guild == null ? null : guild.getVoiceChannelById(voiceId);
    }

    public long getVoiceChannelId()
    {
        return voiceId;
    }

    public Role getRole(Guild guild)
    {
        return guild == null ? null : guild.getRoleById(roleId);
    }

    public long getRoleId()
    {
        return roleId;
    }

    public long getTextChannelId()
    {
        return textId;
    }

    public int getVolume()
    {
        return volume;
    }

    public String getDefaultPlaylist()
    {
        return defaultPlaylist;
    }

    public RepeatMode getRepeatMode()
    {
        return repeatMode;
    }

    public String getPrefix()
    {
        return prefix;
    }

    public double getSkipRatio()
    {
        return skipRatio;
    }

    public QueueType getQueueType()
    {
        return queueType;
    }

    public boolean isAutoplay()
    {
        return autoplay;
    }

    public boolean isStay247()
    {
        return stay247;
    }

    /** Voice channel the bot stays in while 24/7 mode is on (independent of the "music only in channel X" restriction). */
    public long getStayChannelId()
    {
        return stayChannelId;
    }

    public long getRequestChannelId()
    {
        return requestChannelId;
    }

    public long getRequestMessageId()
    {
        return requestMessageId;
    }

    @Override
    public Collection<String> getPrefixes()
    {
        return prefix == null ? Collections.emptySet() : Collections.singleton(prefix);
    }

    // Setters
    public void setTextChannel(TextChannel tc)
    {
        this.textId = tc == null ? 0 : tc.getIdLong();
        save();
    }

    public void setVoiceChannel(VoiceChannel vc)
    {
        this.voiceId = vc == null ? 0 : vc.getIdLong();
        save();
    }

    public void setDJRole(Role role)
    {
        this.roleId = role == null ? 0 : role.getIdLong();
        save();
    }

    public void setVolume(int volume)
    {
        this.volume = volume;
        save();
    }

    public void setDefaultPlaylist(String defaultPlaylist)
    {
        this.defaultPlaylist = defaultPlaylist;
        save();
    }

    public void setRepeatMode(RepeatMode mode)
    {
        this.repeatMode = mode;
        save();
    }

    public void setPrefix(String prefix)
    {
        this.prefix = prefix;
        save();
    }

    public void setSkipRatio(double skipRatio)
    {
        this.skipRatio = skipRatio;
        save();
    }

    public void setQueueType(QueueType queueType)
    {
        this.queueType = queueType;
        save();
    }

    public void setAutoplay(boolean autoplay)
    {
        this.autoplay = autoplay;
        save();
    }

    public void setStay247(boolean stay247, long channelId)
    {
        this.stay247 = stay247;
        this.stayChannelId = stay247 ? channelId : 0;
        save();
    }

    public void setRequestChannel(long channelId, long messageId)
    {
        this.requestChannelId = channelId;
        this.requestMessageId = messageId;
        save();
    }
}
