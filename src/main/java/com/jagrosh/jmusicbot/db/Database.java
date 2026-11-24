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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite persistence. One shared connection guarded by a lock: the workload (settings, history,
 * queue snapshots) is tiny and SQLite serialises writers anyway. WAL mode keeps readers cheap.
 * Every table is keyed by guild id, so guilds are isolated from each other.
 */
public class Database implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger("Database");

    /** Ordered migrations; index + 1 is the schema version. Never edit an applied entry, append instead. */
    private static final String[][] MIGRATIONS = {
        {
            "CREATE TABLE guild_settings ("
                + "guild_id INTEGER PRIMARY KEY,"
                + "text_channel_id INTEGER NOT NULL DEFAULT 0,"
                + "voice_channel_id INTEGER NOT NULL DEFAULT 0,"
                + "dj_role_id INTEGER NOT NULL DEFAULT 0,"
                + "volume INTEGER NOT NULL DEFAULT 100,"
                + "default_playlist TEXT,"
                + "repeat_mode TEXT NOT NULL DEFAULT 'OFF',"
                + "prefix TEXT,"
                + "skip_ratio REAL NOT NULL DEFAULT -1,"
                + "queue_type TEXT NOT NULL DEFAULT 'FAIR',"
                + "autoplay INTEGER NOT NULL DEFAULT 0,"
                + "stay_247 INTEGER NOT NULL DEFAULT 0,"
                + "stay_channel_id INTEGER NOT NULL DEFAULT 0,"
                + "request_channel_id INTEGER NOT NULL DEFAULT 0,"
                + "request_message_id INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE play_history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "guild_id INTEGER NOT NULL,"
                + "user_id INTEGER NOT NULL,"
                + "title TEXT NOT NULL,"
                + "author TEXT,"
                + "uri TEXT,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "played_at INTEGER NOT NULL)",
            "CREATE INDEX idx_history_guild ON play_history(guild_id, id DESC)",
            "CREATE TABLE saved_state ("
                + "guild_id INTEGER PRIMARY KEY,"
                + "voice_channel_id INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 0,"
                + "position_ms INTEGER NOT NULL DEFAULT 0,"
                + "paused INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE saved_queue ("
                + "guild_id INTEGER NOT NULL,"
                + "pos INTEGER NOT NULL,"
                + "track_data TEXT NOT NULL,"
                + "requester_id INTEGER NOT NULL DEFAULT 0,"
                + "requester_name TEXT,"
                + "requester_avatar TEXT,"
                + "query TEXT,"
                + "PRIMARY KEY (guild_id, pos))"
        }
    };

    private final Connection conn;
    private final Object lock = new Object();

    /** Opens (creating if needed) a database file. */
    public Database(Path file) throws SQLException
    {
        try
        {
            Path parent = file.toAbsolutePath().getParent();
            if(parent != null)
                Files.createDirectories(parent);
        }
        catch(Exception e)
        {
            throw new SQLException("Cannot create database directory: " + e.getMessage(), e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try(Statement st = conn.createStatement())
        {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
        LOG.info("Database ready at {}", file.toAbsolutePath());
    }

    private Database(Connection conn) throws SQLException
    {
        this.conn = conn;
        migrate();
    }

    /** In-memory database for tests. */
    public static Database inMemory() throws SQLException
    {
        return new Database(DriverManager.getConnection("jdbc:sqlite::memory:"));
    }

    private void migrate() throws SQLException
    {
        try(Statement st = conn.createStatement())
        {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            int current;
            try(ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version"))
            {
                current = rs.next() ? rs.getInt(1) : 0;
            }
            for(int v = current; v < MIGRATIONS.length; v++)
            {
                LOG.info("Applying database migration {}", v + 1);
                conn.setAutoCommit(false);
                try
                {
                    for(String sql : MIGRATIONS[v])
                        st.execute(sql);
                    st.execute("INSERT INTO schema_version(version) VALUES (" + (v + 1) + ")");
                    conn.commit();
                }
                catch(SQLException e)
                {
                    conn.rollback();
                    throw e;
                }
                finally
                {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    @FunctionalInterface
    public interface Work<T>
    {
        T run(Connection c) throws SQLException;
    }

    /** Runs work holding the database lock. */
    public <T> T with(Work<T> work) throws SQLException
    {
        synchronized(lock)
        {
            return work.run(conn);
        }
    }

    @Override
    public void close()
    {
        synchronized(lock)
        {
            try { conn.close(); } catch(SQLException ignored) { /* closing */ }
        }
    }
}
