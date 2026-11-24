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

import com.jagrosh.jdautilities.command.GuildSettingsManager;
import com.jagrosh.jmusicbot.db.Database;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.entities.Guild;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and persists per-guild {@link Settings} in SQLite. A legacy {@code serversettings.json}
 * is imported once and renamed so nothing is lost when upgrading from the original JMusicBot.
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class SettingsManager implements GuildSettingsManager<Settings>
{
    private final static Logger LOG = LoggerFactory.getLogger("Settings");
    private final static String LEGACY_FILE = "serversettings.json";
    private final Map<Long, Settings> settings = new ConcurrentHashMap<>();
    private final Database db;

    public SettingsManager(Database db)
    {
        this.db = db;
        try
        {
            loadAll();
            importLegacy();
        }
        catch(SQLException e)
        {
            throw new IllegalStateException("Failed to load server settings", e);
        }
    }

    private void loadAll() throws SQLException
    {
        db.with(c ->
        {
            try(PreparedStatement ps = c.prepareStatement("SELECT * FROM guild_settings"); ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                {
                    long id = rs.getLong("guild_id");
                    Settings s = new Settings(this, id);
                    s.load(rs.getLong("text_channel_id"), rs.getLong("voice_channel_id"), rs.getLong("dj_role_id"),
                            rs.getInt("volume"), rs.getString("default_playlist"),
                            parseEnum(RepeatMode.class, rs.getString("repeat_mode"), RepeatMode.OFF),
                            rs.getString("prefix"), rs.getDouble("skip_ratio"),
                            parseEnum(QueueType.class, rs.getString("queue_type"), QueueType.FAIR),
                            rs.getInt("autoplay") != 0, rs.getInt("stay_247") != 0,
                            rs.getLong("request_channel_id"), rs.getLong("request_message_id"));
                    settings.put(id, s);
                }
            }
            return null;
        });
        LOG.info("Loaded settings for {} guilds", settings.size());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def)
    {
        try
        {
            return value == null ? def : Enum.valueOf(type, value);
        }
        catch(IllegalArgumentException e)
        {
            return def;
        }
    }

    /** One-time import of the pre-SQLite serversettings.json. */
    private void importLegacy()
    {
        Path file = OtherUtil.getPath(LEGACY_FILE);
        if(!Files.isRegularFile(file))
            return;
        try
        {
            JSONObject loaded = new JSONObject(Files.readString(file));
            for(String id : loaded.keySet())
            {
                long gid = Long.parseLong(id);
                if(settings.containsKey(gid))
                    continue; // never overwrite data already in the database
                JSONObject o = loaded.getJSONObject(id);
                // Versions 0.3.3 and older stored the repeat mode as a boolean.
                if(!o.has("repeat_mode") && o.has("repeat") && o.getBoolean("repeat"))
                    o.put("repeat_mode", RepeatMode.ALL);
                Settings s = new Settings(this, gid);
                s.load(parseLong(o, "text_channel_id"), parseLong(o, "voice_channel_id"), parseLong(o, "dj_role_id"),
                        o.optInt("volume", 100), o.optString("default_playlist", null),
                        o.has("repeat_mode") ? o.getEnum(RepeatMode.class, "repeat_mode") : RepeatMode.OFF,
                        o.optString("prefix", null), o.optDouble("skip_ratio", -1),
                        o.has("queue_type") ? o.getEnum(QueueType.class, "queue_type") : QueueType.FAIR,
                        false, false, 0, 0);
                settings.put(gid, s);
                save(s);
            }
            Files.move(file, file.resolveSibling(LEGACY_FILE + ".imported"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Imported legacy {} into the database", LEGACY_FILE);
        }
        catch(IOException | JSONException | NumberFormatException e)
        {
            LOG.warn("Failed to import legacy {}: {}", LEGACY_FILE, e.toString());
        }
    }

    private static long parseLong(JSONObject o, String key)
    {
        try
        {
            return o.has(key) ? Long.parseLong(o.getString(key)) : 0;
        }
        catch(NumberFormatException e)
        {
            return 0;
        }
    }

    /**
     * Gets non-null settings for a Guild
     *
     * @param guild the guild to get settings for
     * @return the existing settings, or new settings for that guild
     */
    @Override
    public Settings getSettings(Guild guild)
    {
        return getSettings(guild.getIdLong());
    }

    public Settings getSettings(long guildId)
    {
        return settings.computeIfAbsent(guildId, id -> new Settings(this, id));
    }

    /** Guild ids that have stored settings. */
    public java.util.Set<Long> knownGuilds()
    {
        return java.util.Set.copyOf(settings.keySet());
    }

    void save(Settings s)
    {
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO guild_settings(guild_id,text_channel_id,voice_channel_id,dj_role_id,volume,default_playlist,"
                        + "repeat_mode,prefix,skip_ratio,queue_type,autoplay,stay_247,request_channel_id,request_message_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(guild_id) DO UPDATE SET text_channel_id=excluded.text_channel_id,"
                        + "voice_channel_id=excluded.voice_channel_id,dj_role_id=excluded.dj_role_id,volume=excluded.volume,"
                        + "default_playlist=excluded.default_playlist,repeat_mode=excluded.repeat_mode,prefix=excluded.prefix,"
                        + "skip_ratio=excluded.skip_ratio,queue_type=excluded.queue_type,autoplay=excluded.autoplay,"
                        + "stay_247=excluded.stay_247,request_channel_id=excluded.request_channel_id,"
                        + "request_message_id=excluded.request_message_id"))
                {
                    int i = 1;
                    ps.setLong(i++, s.getGuildId());
                    ps.setLong(i++, s.getTextChannelId());
                    ps.setLong(i++, s.getVoiceChannelId());
                    ps.setLong(i++, s.getRoleId());
                    ps.setInt(i++, s.getVolume());
                    ps.setString(i++, s.getDefaultPlaylist());
                    ps.setString(i++, s.getRepeatMode().name());
                    ps.setString(i++, s.getPrefix());
                    ps.setDouble(i++, s.getSkipRatio());
                    ps.setString(i++, s.getQueueType().name());
                    ps.setInt(i++, s.isAutoplay() ? 1 : 0);
                    ps.setInt(i++, s.isStay247() ? 1 : 0);
                    ps.setLong(i++, s.getRequestChannelId());
                    ps.setLong(i, s.getRequestMessageId());
                    ps.executeUpdate();
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.error("Failed to persist settings for guild {}", s.getGuildId(), e);
        }
    }
}
