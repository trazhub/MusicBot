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
package com.jagrosh.jmusicbot.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolverTest
{
    @Test
    void parsesSpotifyLinks()
    {
        assertEquals(new SpotifyResolver.Link("track", "4cOdK2wGLETKBW3PvgPWqT"),
                SpotifyResolver.parse("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123"));
        assertEquals(new SpotifyResolver.Link("playlist", "37i9dQZF1DXcBWIGoYBM5M"),
                SpotifyResolver.parse("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"));
        assertEquals(new SpotifyResolver.Link("album", "1DFixLWuPkv3KT3TnV35m3"),
                SpotifyResolver.parse("spotify:album:1DFixLWuPkv3KT3TnV35m3"));
        assertNull(SpotifyResolver.parse("https://example.com/track/4cOdK2wGLETKBW3PvgPWqT"));
        assertNull(SpotifyResolver.parse("https://open.spotify.com.evil.example/track/4cOdK2wGLETKBW3PvgPWqT"));
        assertNull(SpotifyResolver.parse("never gonna give you up"));
        assertNull(SpotifyResolver.parse(null));
    }

    @Test
    void parsesTrackEmbedData()
    {
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{\"type\":\"track\",\"name\":\"Never Gonna Give You Up\","
                + "\"artists\":[{\"name\":\"Rick Astley\"}],\"duration\":213573,"
                + "\"visualIdentity\":{\"image\":[{\"url\":\"small\",\"maxWidth\":64},{\"url\":\"big\",\"maxWidth\":640}]}}}}}}}"
                + "</script></html>";
        SpotifyResolver.Collection c = SpotifyResolver.parseEmbed(html, 100);
        assertNotNull(c);
        assertEquals("track", c.type());
        assertEquals("big", c.artworkUrl());
        assertEquals(1, c.items().size());
        assertEquals("Rick Astley - Never Gonna Give You Up", c.items().get(0).searchQuery());
        assertEquals(213573, c.items().get(0).durationMs());
    }

    @Test
    void parsesPlaylistEmbedDataAndHonoursLimit()
    {
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{\"type\":\"playlist\",\"name\":\"Mix\","
                + "\"coverArt\":{\"sources\":[{\"url\":\"cover\"}]},\"trackList\":["
                + "{\"title\":\"One\",\"subtitle\":\"A, B\",\"duration\":1000},"
                + "{\"title\":\"Two\",\"subtitle\":\"C\",\"duration\":2000},"
                + "{\"title\":\"Three\",\"subtitle\":\"D\",\"duration\":3000}]}}}}}}</script>";
        SpotifyResolver.Collection c = SpotifyResolver.parseEmbed(html, 2);
        assertEquals("Mix", c.name());
        assertEquals("cover", c.artworkUrl());
        assertEquals(List.of("A, B - One", "C - Two"), c.items().stream().map(SpotifyResolver.Item::searchQuery).toList());
    }

    @Test
    void embedParserRejectsGarbage()
    {
        assertNull(SpotifyResolver.parseEmbed("<html>nothing here</html>", 10));
        assertNull(SpotifyResolver.parseEmbed("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{}}</script>", 10));
    }

    @Test
    void parsesRadioStations()
    {
        String json = "[{\"name\":\"  Lofi FM \",\"url_resolved\":\"http://stream.example/lofi.mp3\",\"country\":\"France\",\"codec\":\"MP3\",\"bitrate\":128,\"favicon\":\"\"},"
                + "{\"name\":\"Broken\",\"url_resolved\":\"\",\"url\":\"\"}]";
        List<RadioResolver.Station> s = RadioResolver.parse(json);
        assertEquals(1, s.size());
        assertEquals("Lofi FM", s.get(0).name());
        assertEquals("http://stream.example/lofi.mp3", s.get(0).streamUrl());
        assertEquals(128, s.get(0).bitrate());
    }
}
