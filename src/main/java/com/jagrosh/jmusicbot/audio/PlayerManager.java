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
package com.jagrosh.jmusicbot.audio;

import com.dunctebot.sourcemanagers.DuncteBotSources;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.security.UrlGuard;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.DecodedTrackHolder;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.Web;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Base64;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerManager extends DefaultAudioPlayerManager
{
    private static final Logger LOG = LoggerFactory.getLogger("PlayerManager");
    private final Bot bot;
    private YoutubeAudioSourceManager youtube;

    public PlayerManager(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        BotConfig config = bot.getConfig();
        UrlGuard.setAllowPrivate(config.allowPrivateUrls());

        // SSRF: every connection made by generic HTTP sources (including redirects) resolves through this filter,
        // so a public hostname can't redirect or rebind to an internal address.
        setHttpBuilderConfigurator(builder -> builder.setDnsResolver(host ->
        {
            InetAddress[] all = InetAddress.getAllByName(host);
            if(UrlGuard.isAllowPrivate())
                return all;
            for(InetAddress a : all)
                if(UrlGuard.isPrivate(a))
                    throw new UnknownHostException("Blocked non-public address for " + host);
            return all;
        }));

        TransformativeAudioSourceManager.createTransforms(config.getTransforms()).forEach(t -> registerSourceManager(t));

        youtube = new YoutubeAudioSourceManager(true);
        youtube.setPlaylistPageCount(config.getMaxYTPlaylistPages());
        configureYoutubeAuth(config);
        registerSourceManager(youtube);

        registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        registerSourceManager(new BandcampAudioSourceManager());
        registerSourceManager(new VimeoAudioSourceManager());
        registerSourceManager(new TwitchStreamAudioSourceManager());
        registerSourceManager(new BeamAudioSourceManager());
        registerSourceManager(new GetyarnAudioSourceManager());
        registerSourceManager(new NicoAudioSourceManager());
        registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));
        if(config.allowLocalFiles())
        {
            LOG.warn("allowlocalfiles is enabled: users can play any audio file readable by the bot process");
            AudioSourceManagers.registerLocalSource(this);
        }
        DuncteBotSources.registerAll(this, "en-US");
    }

    private void configureYoutubeAuth(BotConfig config)
    {
        String token = config.getYoutubeRefreshToken();
        if(token != null)
        {
            if(token.equalsIgnoreCase("LOGIN") || token.equalsIgnoreCase("AUTH"))
            {
                LOG.warn("YouTube OAuth login requested: follow the instructions that appear in this log, then store the "
                        + "printed refresh token in YOUTUBE_REFRESH_TOKEN. Use a throwaway Google account.");
                youtube.useOauth2(null, false);
            }
            else
                youtube.useOauth2(token, true);
        }
        if(config.getYoutubePoToken() != null && config.getYoutubeVisitorData() != null)
            Web.setPoTokenAndVisitorData(config.getYoutubePoToken(), config.getYoutubeVisitorData());
        else if(token == null)
            LOG.info("No YouTube OAuth token or poToken configured. If YouTube answers \"Sign in to confirm you're not a bot\" "
                    + "on this server, see docs/DEPLOYMENT.md (YouTube access).");
    }

    public Bot getBot()
    {
        return bot;
    }

    public boolean hasHandler(Guild guild)
    {
        return guild.getAudioManager().getSendingHandler()!=null;
    }

    public AudioHandler setUpHandler(Guild guild)
    {
        AudioHandler handler;
        if(guild.getAudioManager().getSendingHandler()==null)
        {
            AudioPlayer player = createPlayer();
            player.setVolume(bot.getSettingsManager().getSettings(guild).getVolume());
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
            guild.getAudioManager().setConnectionListener(bot.getWatchdog().listenerFor(guild.getIdLong()));
        }
        else
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler;
    }

    /** Serialises a track (identifier, position-independent state) for the queue snapshot. */
    public String encode(AudioTrack track)
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            encodeTrack(new MessageOutput(new DataOutputStream(bytes)), track);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
        catch(Exception e)
        {
            LOG.debug("Could not encode track {}: {}", track.getIdentifier(), e.toString());
            return null;
        }
    }

    /** Inverse of {@link #encode}; returns null if the data can no longer be decoded. */
    public AudioTrack decode(String data)
    {
        try
        {
            DecodedTrackHolder holder = decodeTrack(new MessageInput(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))));
            return holder == null ? null : holder.decodedTrack;
        }
        catch(Exception e)
        {
            LOG.debug("Could not decode saved track: {}", e.toString());
            return null;
        }
    }
}
