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
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;

/** Permission and precondition checks shared by text commands, slash commands, buttons and the request channel. */
public final class Access
{
    private Access() {}

    /** Bot owner, Manage Server, or holder of the configured DJ role (a DJ role of @everyone opens DJ commands to all). */
    public static boolean isDJ(Bot bot, Guild guild, Member member)
    {
        if(member.getId().equals(Long.toString(bot.getConfig().getOwnerId())))
            return true;
        if(member.hasPermission(Permission.MANAGE_SERVER))
            return true;
        Settings settings = bot.getSettingsManager().getSettings(guild);
        Role dj = settings.getRole(guild);
        return dj != null && (member.getRoles().contains(dj) || dj.getIdLong() == guild.getIdLong());
    }

    public static boolean isAdmin(Bot bot, Member member)
    {
        return member.getId().equals(Long.toString(bot.getConfig().getOwnerId())) || member.hasPermission(Permission.MANAGE_SERVER);
    }

    /** @return an error message if music commands are restricted to another text channel, else null. */
    public static String checkTextChannel(Bot bot, Guild guild, Channel channel)
    {
        Settings s = bot.getSettingsManager().getSettings(guild);
        if(s.getTextChannelId() != 0 && s.getTextChannelId() != channel.getIdLong() && s.getRequestChannelId() != channel.getIdLong())
            return "You can only use music commands in <#" + s.getTextChannelId() + ">.";
        return null;
    }

    /**
     * Checks that the member is listening in the bot's voice channel and optionally joins it.
     *
     * @return an error message, or null if everything is fine
     */
    public static String checkListening(Bot bot, Guild guild, Member member, boolean connect)
    {
        Settings settings = bot.getSettingsManager().getSettings(guild);
        bot.getPlayerManager().setUpHandler(guild);
        AudioChannel current = guild.getSelfMember().getVoiceState().getChannel();
        if(current == null)
            current = settings.getVoiceChannel(guild);
        GuildVoiceState userState = member.getVoiceState();
        if(userState == null || !userState.inAudioChannel() || userState.isDeafened()
                || (current != null && !userState.getChannel().equals(current)))
            return "You must be listening in " + (current == null ? "a voice channel" : current.getAsMention()) + " to use that!";
        AudioChannel afk = guild.getAfkChannel();
        if(afk != null && afk.equals(userState.getChannel()))
            return "You cannot use that command in an AFK channel!";
        if(connect && !guild.getSelfMember().getVoiceState().inAudioChannel())
        {
            try
            {
                guild.getAudioManager().openAudioConnection(userState.getChannel());
                ((AudioHandler) guild.getAudioManager().getSendingHandler()).onJoined(userState.getChannel().getIdLong());
            }
            catch(PermissionException ex)
            {
                return "I am unable to connect to " + userState.getChannel().getAsMention() + "!";
            }
        }
        return null;
    }
}
