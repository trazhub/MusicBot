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
package com.jagrosh.jmusicbot.ui;

import com.jagrosh.jmusicbot.settings.RepeatMode;
import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;

/** Button ids and layouts for the now-playing panel and paged lists. */
public final class Controls
{
    public static final String PREFIX = "jmb:";
    public static final String PREVIOUS = PREFIX + "prev";
    public static final String PAUSE = PREFIX + "pause";
    public static final String SKIP = PREFIX + "skip";
    public static final String STOP = PREFIX + "stop";
    public static final String LOOP = PREFIX + "loop";
    public static final String SHUFFLE = PREFIX + "shuffle";
    public static final String VOL_DOWN = PREFIX + "vdown";
    public static final String VOL_UP = PREFIX + "vup";
    public static final String QUEUE = PREFIX + "queue";
    public static final String AUTOPLAY = PREFIX + "autoplay";
    /** Queue pagination: jmb:qp:&lt;page&gt;. */
    public static final String QUEUE_PAGE = PREFIX + "qp:";
    public static final String QUEUE_CLEAR = PREFIX + "qclear";
    /** History pagination: jmb:hp:&lt;page&gt;. */
    public static final String HISTORY_PAGE = PREFIX + "hp:";

    private Controls() {}

    public static List<ActionRow> nowPlaying(boolean idle, boolean paused, RepeatMode repeat, boolean autoplay)
    {
        Button loop = Button.of(repeat == RepeatMode.OFF ? ButtonStyle.SECONDARY : ButtonStyle.SUCCESS, LOOP,
                Emoji.fromUnicode(repeat == RepeatMode.SINGLE ? "🔂" : "🔁"));
        Button auto = Button.of(autoplay ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY, AUTOPLAY, Emoji.fromUnicode("♾️"));
        return List.of(
            ActionRow.of(
                Button.secondary(PREVIOUS, Emoji.fromUnicode("⏮️")).withDisabled(idle),
                Button.primary(PAUSE, Emoji.fromUnicode(paused ? "▶️" : "⏸️")).withDisabled(idle),
                Button.secondary(SKIP, Emoji.fromUnicode("⏭️")).withDisabled(idle),
                Button.danger(STOP, Emoji.fromUnicode("⏹️")).withDisabled(idle),
                loop),
            ActionRow.of(
                Button.secondary(SHUFFLE, Emoji.fromUnicode("🔀")),
                Button.secondary(VOL_DOWN, Emoji.fromUnicode("🔉")),
                Button.secondary(VOL_UP, Emoji.fromUnicode("🔊")),
                Button.secondary(QUEUE, Emoji.fromUnicode("📜")),
                auto));
    }

    public static ActionRow pager(String prefix, int page, int pages, boolean withClear)
    {
        Button prev = Button.secondary(prefix + Math.max(0, page - 1), Emoji.fromUnicode("◀️")).withDisabled(page <= 0);
        Button label = Button.secondary(PREFIX + "noop", (page + 1) + " / " + pages).asDisabled();
        Button next = Button.secondary(prefix + Math.min(pages - 1, page + 1), Emoji.fromUnicode("▶️")).withDisabled(page >= pages - 1);
        if(withClear)
            return ActionRow.of(prev, label, next, Button.danger(QUEUE_CLEAR, Emoji.fromUnicode("🗑️")),
                    Button.secondary(SHUFFLE, Emoji.fromUnicode("🔀")));
        return ActionRow.of(prev, label, next);
    }
}
