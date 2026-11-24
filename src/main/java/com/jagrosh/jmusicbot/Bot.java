/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.audio.AloneInVoiceHandler;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.NowplayingHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.db.Database;
import com.jagrosh.jmusicbot.db.HistoryStore;
import com.jagrosh.jmusicbot.db.QueueStore;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.health.HealthServer;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.resolve.SpotifyResolver;
import com.jagrosh.jmusicbot.security.RateLimiter;
import com.jagrosh.jmusicbot.service.PersistenceService;
import com.jagrosh.jmusicbot.service.PlaybackActions;
import com.jagrosh.jmusicbot.service.TrackLoader;
import com.jagrosh.jmusicbot.service.VoiceWatchdog;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.ui.RequestChannels;
import com.jagrosh.jmusicbot.ui.SlashHandler;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central object that owns every service of the bot.
 *
 * @author John Grosh (jagrosh)
 */
public class Bot
{
    private static final Logger LOG = LoggerFactory.getLogger("Bot");

    private final EventWaiter waiter;
    private final ScheduledExecutorService threadpool;
    private final BotConfig config;
    private final SettingsManager settings;
    private final Database database;
    private final HistoryStore history;
    private final QueueStore queueStore;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final NowplayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;
    private final RateLimiter rateLimiter;
    private final PlaybackActions actions;
    private final TrackLoader loader;
    private final RequestChannels requestChannels;
    private final VoiceWatchdog watchdog;
    private final PersistenceService persistence;
    private final HealthServer health = new HealthServer();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private boolean shuttingDown = false;
    private JDA jda;
    private GUI gui;

    public Bot(EventWaiter waiter, BotConfig config, SettingsManager settings, Database database)
    {
        this.waiter = waiter;
        this.config = config;
        this.settings = settings;
        this.database = database;
        this.history = new HistoryStore(database);
        this.queueStore = new QueueStore(database);
        this.playlists = new PlaylistLoader(config);
        this.threadpool = Executors.newScheduledThreadPool(4, r ->
        {
            Thread t = new Thread(r, "jmusicbot-worker");
            t.setDaemon(true);
            return t;
        });
        this.rateLimiter = new RateLimiter(config.getRateLimitCount(), config.getRateLimitSeconds() * 1000L);
        this.players = new PlayerManager(this);
        this.players.init();
        this.nowplaying = new NowplayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
        this.actions = new PlaybackActions(this);
        this.loader = new TrackLoader(this, new SpotifyResolver(config.getSpotifyClientId(), config.getSpotifyClientSecret(), config.getSpotifyMaxTracks()));
        this.requestChannels = new RequestChannels(this);
        this.watchdog = new VoiceWatchdog(this);
        this.persistence = new PersistenceService(this);
        this.threadpool.scheduleWithFixedDelay(rateLimiter::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    /** Called once when Discord reports ready: starts background services, restores players and registers slash commands. */
    public void onReady(SlashHandler slash)
    {
        if(!ready.compareAndSet(false, true))
            return;
        watchdog.init();
        persistence.init();
        requestChannels.init();
        persistence.restoreAll();
        if(config.useSlashCommands())
            slash.register(jda);
        if(config.getHealthPort() > 0)
        {
            try
            {
                health.start(this, config.getHealthPort());
            }
            catch(Exception e)
            {
                LOG.warn("Could not start health endpoint on port {}: {}", config.getHealthPort(), e.toString());
            }
        }
    }

    public BotConfig getConfig()
    {
        return config;
    }

    public SettingsManager getSettingsManager()
    {
        return settings;
    }

    public Database getDatabase()
    {
        return database;
    }

    public HistoryStore getHistoryStore()
    {
        return history;
    }

    public QueueStore getQueueStore()
    {
        return queueStore;
    }

    public EventWaiter getWaiter()
    {
        return waiter;
    }

    public ScheduledExecutorService getThreadpool()
    {
        return threadpool;
    }

    public PlayerManager getPlayerManager()
    {
        return players;
    }

    public PlaylistLoader getPlaylistLoader()
    {
        return playlists;
    }

    public NowplayingHandler getNowplayingHandler()
    {
        return nowplaying;
    }

    public AloneInVoiceHandler getAloneInVoiceHandler()
    {
        return aloneInVoiceHandler;
    }

    public RateLimiter getRateLimiter()
    {
        return rateLimiter;
    }

    public PlaybackActions getActions()
    {
        return actions;
    }

    public TrackLoader getLoader()
    {
        return loader;
    }

    public RequestChannels getRequestChannels()
    {
        return requestChannels;
    }

    public VoiceWatchdog getWatchdog()
    {
        return watchdog;
    }

    public PersistenceService getPersistence()
    {
        return persistence;
    }

    public JDA getJDA()
    {
        return jda;
    }

    /** Leaves voice on purpose (the watchdog will not rejoin). */
    public void closeAudioConnection(long guildId)
    {
        Guild guild = jda.getGuildById(guildId);
        if(guild != null)
        {
            if(guild.getAudioManager().getSendingHandler() instanceof AudioHandler ah)
                ah.markIntentionalLeave();
            threadpool.submit(() -> guild.getAudioManager().closeAudioConnection());
        }
    }

    public void resetGame()
    {
        Activity game = config.getGame()==null || config.getGame().getName().equalsIgnoreCase("none") ? null : config.getGame();
        if(!Objects.equals(jda.getPresence().getActivity(), game))
            jda.getPresence().setActivity(game);
    }

    public void shutdown()
    {
        if(shuttingDown)
            return;
        shuttingDown = true;
        if(jda == null) // login failed before JDA was assigned: nothing to tear down
        {
            health.stop();
            threadpool.shutdownNow();
            database.close();
            return;
        }
        // snapshot every player BEFORE tearing anything down so the queue can resume after a restart
        try
        {
            persistence.flushNow();
        }
        catch(Exception e)
        {
            LOG.warn("Final queue snapshot failed: {}", e.toString());
        }
        health.stop();
        threadpool.shutdownNow();
        if(jda.getStatus()!=JDA.Status.SHUTTING_DOWN)
        {
            jda.getGuilds().stream().forEach(g ->
            {
                AudioHandler ah = (AudioHandler)g.getAudioManager().getSendingHandler();
                if(ah!=null)
                {
                    ah.markIntentionalLeave(); // not a connection loss: don't reconnect while shutting down
                    ah.getPlayer().destroy();
                }
                g.getAudioManager().closeAudioConnection();
            });
            jda.shutdown();
        }
        database.close();
        if(gui!=null)
            gui.dispose();
        System.exit(0);
    }

    public void setJDA(JDA jda)
    {
        this.jda = jda;
    }

    public void setGUI(GUI gui)
    {
        this.gui = gui;
    }
}
