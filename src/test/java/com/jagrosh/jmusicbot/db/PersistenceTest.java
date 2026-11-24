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

import static org.junit.jupiter.api.Assertions.*;

import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceTest
{
    private Database db;

    @BeforeEach
    void open() throws Exception
    {
        db = Database.inMemory();
    }

    @AfterEach
    void close()
    {
        db.close();
    }

    @Test
    void settingsPersistAndAreIsolatedPerGuild()
    {
        SettingsManager m = new SettingsManager(db);
        Settings a = m.getSettings(111L);
        Settings b = m.getSettings(222L);
        a.setVolume(42);
        a.setAutoplay(true);
        a.setStay247(true);
        a.setSkipRatio(0.8);
        a.setRepeatMode(RepeatMode.ALL);
        a.setQueueType(QueueType.LINEAR);
        a.setRequestChannel(555L, 666L);
        a.setPrefix("!");

        // a fresh manager on the same database must see the same data
        SettingsManager reloaded = new SettingsManager(db);
        Settings ra = reloaded.getSettings(111L);
        assertEquals(42, ra.getVolume());
        assertTrue(ra.isAutoplay());
        assertTrue(ra.isStay247());
        assertEquals(0.8, ra.getSkipRatio(), 1e-9);
        assertEquals(RepeatMode.ALL, ra.getRepeatMode());
        assertEquals(QueueType.LINEAR, ra.getQueueType());
        assertEquals(555L, ra.getRequestChannelId());
        assertEquals(666L, ra.getRequestMessageId());
        assertEquals("!", ra.getPrefix());

        // the other guild was never touched
        assertEquals(100, b.getVolume());
        assertFalse(b.isAutoplay());
        assertFalse(b.isStay247());
        assertNull(b.getPrefix());
        assertEquals(RepeatMode.OFF, b.getRepeatMode());
    }

    @Test
    void historyIsPerGuildOrderedAndBounded()
    {
        HistoryStore h = new HistoryStore(db);
        h.record(1, 10, "First", "A", "https://x/1", 1000);
        h.record(1, 11, "Second", "B", "https://x/2", 2000);
        h.record(2, 12, "Other guild", "C", "https://x/3", 3000);

        List<HistoryStore.Entry> g1 = h.recent(1, 10, 0);
        assertEquals(2, g1.size());
        assertEquals("Second", g1.get(0).title(), "most recent first");
        assertEquals(1, h.recent(2, 10, 0).size());
        assertEquals(2, h.count(1));
        assertEquals(List.of("Second"), h.suggest(1, "sec", 5));
        assertTrue(h.suggest(1, "Other", 5).isEmpty(), "suggestions must not leak across guilds");
        assertTrue(h.suggest(1, "%", 5).isEmpty(), "LIKE wildcards must be escaped");

        for(int i = 0; i < 520; i++)
            h.record(3, 1, "t" + i, null, null, 0);
        assertEquals(500, h.count(3), "history is trimmed to the newest entries per guild");
        assertEquals("t519", h.recent(3, 1, 0).get(0).title());
    }

    @Test
    void queueSnapshotRoundTripsAndClears()
    {
        QueueStore q = new QueueStore(db);
        var cur = new QueueStore.SavedTrack("cur-data", 7, "alice", "http://a", "query1");
        var t1 = new QueueStore.SavedTrack("t1", 8, "bob", null, null);
        var t2 = new QueueStore.SavedTrack("t2", 0, null, null, null);
        q.save(new QueueStore.State(5, 99, true, 12_345, true, cur, List.of(t1, t2), 0));

        QueueStore.State s = q.load(5);
        assertNotNull(s);
        assertEquals(99, s.voiceChannelId());
        assertTrue(s.active());
        assertTrue(s.paused());
        assertEquals(12_345, s.positionMs());
        assertEquals("cur-data", s.current().data());
        assertEquals("alice", s.current().requesterName());
        assertEquals(List.of("t1", "t2"), s.queue().stream().map(QueueStore.SavedTrack::data).toList());
        assertTrue(s.updatedAt() > 0);
        assertEquals(List.of(5L), q.activeGuilds());
        assertNull(q.load(6));

        // saving again replaces rather than appends
        q.save(new QueueStore.State(5, 99, true, 0, false, null, List.of(t2), 0));
        assertEquals(1, q.load(5).queue().size());
        assertNull(q.load(5).current());

        q.clear(5);
        assertTrue(q.load(5).queue().isEmpty());
        assertTrue(q.activeGuilds().isEmpty());
    }

    @Test
    void migrationsAreIdempotent(@TempDir Path dir) throws Exception
    {
        Path file = dir.resolve("test.db");
        try(Database d1 = new Database(file))
        {
            new HistoryStore(d1).record(1, 1, "kept", null, null, 0);
        }
        try(Database d2 = new Database(file)) // re-open: migrations must not re-run or lose data
        {
            assertEquals(1, new HistoryStore(d2).count(1));
        }
    }
}
