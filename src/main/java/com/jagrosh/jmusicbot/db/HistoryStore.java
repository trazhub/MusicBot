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

/** Per-guild play history (what played, who asked for it, when). */
public class HistoryStore
{
    private static final Logger LOG = LoggerFactory.getLogger("History");
    private static final int KEEP_PER_GUILD = 500;

    public record Entry(String title, String author, String uri, long durationMs, long userId, long playedAt) {}

    private final Database db;

    public HistoryStore(Database db)
    {
        this.db = db;
    }

    public void record(long guildId, long userId, String title, String author, String uri, long durationMs)
    {
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO play_history(guild_id,user_id,title,author,uri,duration_ms,played_at) VALUES (?,?,?,?,?,?,?)"))
                {
                    ps.setLong(1, guildId);
                    ps.setLong(2, userId);
                    ps.setString(3, title == null ? "Unknown" : title);
                    ps.setString(4, author);
                    ps.setString(5, uri);
                    ps.setLong(6, durationMs);
                    ps.setLong(7, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                // keep the table bounded per guild
                try(PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM play_history WHERE guild_id=? AND id <= (SELECT id FROM play_history WHERE guild_id=? "
                        + "ORDER BY id DESC LIMIT 1 OFFSET ?)"))
                {
                    ps.setLong(1, guildId);
                    ps.setLong(2, guildId);
                    ps.setInt(3, KEEP_PER_GUILD);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to record history for guild {}: {}", guildId, e.toString());
        }
    }

    /** Most recent first. */
    public List<Entry> recent(long guildId, int limit, int offset)
    {
        List<Entry> out = new ArrayList<>();
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement(
                        "SELECT title,author,uri,duration_ms,user_id,played_at FROM play_history WHERE guild_id=? "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?"))
                {
                    ps.setLong(1, guildId);
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        while(rs.next())
                            out.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)));
                    }
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("Failed to read history for guild {}: {}", guildId, e.toString());
        }
        return out;
    }

    public int count(long guildId)
    {
        try
        {
            return db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM play_history WHERE guild_id=?"))
                {
                    ps.setLong(1, guildId);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        }
        catch(SQLException e)
        {
            return 0;
        }
    }

    /** Distinct recently played titles matching a prefix/substring; powers slash command autocomplete. */
    public List<String> suggest(long guildId, String fragment, int limit)
    {
        List<String> out = new ArrayList<>();
        try
        {
            db.with(c ->
            {
                try(PreparedStatement ps = c.prepareStatement(
                        "SELECT title FROM play_history WHERE guild_id=? AND title LIKE ? ESCAPE '\\' "
                        + "GROUP BY title ORDER BY MAX(id) DESC LIMIT ?"))
                {
                    ps.setLong(1, guildId);
                    ps.setString(2, "%" + fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                    ps.setInt(3, limit);
                    try(ResultSet rs = ps.executeQuery())
                    {
                        while(rs.next())
                            out.add(rs.getString(1));
                    }
                }
                return null;
            });
        }
        catch(SQLException e)
        {
            LOG.warn("History suggest failed: {}", e.toString());
        }
        return out;
    }
}
