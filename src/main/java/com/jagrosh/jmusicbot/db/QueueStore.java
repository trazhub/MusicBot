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
package com.jagrosh.jmusicbot.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot of a guild's player (voice channel, current track + position, queue) so playback survives
 * restarts. The current track is stored at position -1, queued tracks at 0..n.
 */
public class QueueStore
{
    private static final Logger LOG = LoggerFactory.getLogger("QueueStore");

    public record SavedTrack(String data, long requesterId, String requesterName, String requesterAvatar, String query) {}

    public record State(long guildId, long voiceChannelId, boolean active, long positionMs, boolean paused,
                        SavedTrack current, List<SavedTrack> queue, long updatedAt) {}

    private final Database db;

    public QueueStore(Database db)
    {
        this.db = db;
    }

    public void save(State s)
    {
        try
        {
            db.with(c ->
            {
                c.setAutoCommit(false);
                try
                {
                    try(PreparedStatement ps = c.prepareStatement("DELETE FROM saved_queue WHERE guild_id=?"))
                    {
                        ps.setLong(1, s.guildId());
                        ps.executeUpdate();
                    }
                    try(PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO saved_state(guild_id,voice_channel_id,active,position_ms,paused,updated_at) VALUES (?,?,?,?,?,?) "
                            + "ON CONFLICT(guild_id) DO UPDATE SET voice_channel_id=excluded.voice_channel_id,"
                            + "active=excluded.active,position_ms=excluded.position_ms,paused=excluded.paused,updated_at=excluded.updated_at"))
                    {
                        ps.setLong(1, s.guildId());
                        ps.setLong(2, s.voiceChannelId());
                        ps.setInt(3, s.active() ? 1 : 0);
                        ps.setLong(4, s.positionMs());
                        ps.setInt(5, s.paused() ? 1 : 0);
                        ps.setLong(6, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    try(PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO saved_queue(guild_id,pos,track_data,requester_id,requester_name,requester_avatar,query) VALUES (?,?,?,?,?,?,?)"))
                    {
                        if(s.current() != null)
                            add(ps, s.guildId(), -1, s.current());
                        for(int i = 0; i < s.queue().size(); i++)
                            add(ps, s.guildId(), i, s.queue().get(i));
                        ps.executeBatch();
                    }
                    c.commit();
                }
                catch(SQLException e)
                {
                    c.rollback();
                    throw e;
                }
                finally
                {
                    c.setAutoCommit(true);
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to save queue for guild {}: {}", s.guildId(), e.toString());
        }
    }

    private static void add(PreparedStatement ps, long guildId, int pos, SavedTrack t) throws SQLException
    {
        ps.setLong(1, guildId);
        ps.setInt(2, pos);
        ps.setString(3, t.data());
        ps.setLong(4, t.requesterId());
        ps.setString(5, t.requesterName());
        ps.setString(6, t.requesterAvatar());
        ps.setString(7, t.query());
        ps.addBatch();
    }

    /** Guild ids that had an active player when last saved. */
    public List<Long> activeGuilds()
    {
        List<Long> out = new ArrayList<>();
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement("SELECT guild_id FROM saved_state WHERE active=1");
                    ResultSet rs = ps.executeQuery())
                {
                    while(rs.next())
                        out.add(rs.getLong(1));
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to list saved players: {}", e.toString());
        }
        return out;
    }

    public State load(long guildId)
    {
        try
        {
            return db.with(c ->
            {
                long vc = 0, pos = 0, updated = 0;
                boolean active = false, paused = false;
                try(PreparedStatement ps = c.prepareStatement("SELECT voice_channel_id,active,position_ms,paused,updated_at FROM saved_state WHERE guild_id=?"))
                {
                    ps.setLong(1, guildId);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        if(!rs.next())
                            return null;
                        vc = rs.getLong(1);
                        active = rs.getInt(2) != 0;
                        pos = rs.getLong(3);
                        paused = rs.getInt(4) != 0;
                        updated = rs.getLong(5);
                    }
                }
                SavedTrack current = null;
                List<SavedTrack> queue = new ArrayList<>();
                try(PreparedStatement ps = c.prepareStatement(
                        "SELECT pos,track_data,requester_id,requester_name,requester_avatar,query FROM saved_queue WHERE guild_id=? ORDER BY pos"))
                {
                    ps.setLong(1, guildId);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        while(rs.next())
                        {
                            SavedTrack t = new SavedTrack(rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6));
                            if(rs.getInt(1) < 0)
                                current = t;
                            else
                                queue.add(t);
                        }
                    }
                }
                return new State(guildId, vc, active, pos, paused, current, queue, updated);
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to load queue for guild {}: {}", guildId, e.toString());
            return null;
        }
    }

    /** Marks a guild's player inactive and drops its stored queue. */
    public void clear(long guildId)
    {
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement("DELETE FROM saved_queue WHERE guild_id=?"))
                {
                    ps.setLong(1, guildId);
                    ps.executeUpdate();
                }
                try(PreparedStatement ps = c.prepareStatement("UPDATE saved_state SET active=0,position_ms=0 WHERE guild_id=?"))
                {
                    ps.setLong(1, guildId);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to clear queue for guild {}: {}", guildId, e.toString());
        }
    }
}
