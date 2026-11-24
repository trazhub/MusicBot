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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves Spotify links to METADATA ONLY (title, artists, duration, artwork). Spotify audio can't be streamed
 * by third parties, so callers must match each item to a playable source (YouTube) by search.
 *
 * Two strategies, tried in order:
 * <ol>
 *   <li>Official Web API with client credentials, if configured. Spotify's 2026 Development Mode changes restrict
 *       which endpoints such apps may call, so any failure falls through to the next strategy.</li>
 *   <li>Public embed page data ({@code open.spotify.com/embed/...}), which needs no credentials. This is an
 *       undocumented page format and may change without notice.</li>
 * </ol>
 */
public class SpotifyResolver
{
    private static final Logger LOG = LoggerFactory.getLogger("Spotify");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(track|album|playlist|artist)/([A-Za-z0-9]{10,32})(?:[/?#].*)?$");
    private static final Pattern URI_PATTERN = Pattern.compile("^spotify:(track|album|playlist|artist):([A-Za-z0-9]{10,32})$");
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    public record Link(String type, String id) {}

    public record Item(String title, String artist, long durationMs)
    {
        /** Search phrase used to find a playable match. */
        public String searchQuery()
        {
            return artist == null || artist.isBlank() ? title : artist + " - " + title;
        }
    }

    public record Collection(String type, String name, String artworkUrl, List<Item> items) {}

    private final String clientId;
    private final String clientSecret;
    private final int maxTracks;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private String token;
    private long tokenExpiresAt;

    public SpotifyResolver(String clientId, String clientSecret, int maxTracks)
    {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.maxTracks = Math.max(1, maxTracks);
    }

    public boolean hasCredentials()
    {
        return clientId != null && clientSecret != null;
    }

    /** @return the parsed link, or null if the input is not a Spotify link. */
    public static Link parse(String input)
    {
        if(input == null)
            return null;
        String s = input.trim();
        Matcher m = URL_PATTERN.matcher(s);
        if(m.matches())
            return new Link(m.group(1), m.group(2));
        m = URI_PATTERN.matcher(s);
        return m.matches() ? new Link(m.group(1), m.group(2)) : null;
    }

    public Collection fetch(Link link) throws IOException
    {
        if(link.type().equals("artist"))
            throw new IOException("Spotify artist links are not supported; use a track, album or playlist link.");
        if(hasCredentials())
        {
            try
            {
                Collection c = fetchApi(link);
                if(c != null && !c.items().isEmpty())
                    return c;
            }
            catch(Exception e)
            {
                LOG.debug("Spotify API lookup failed, falling back to embed data: {}", e.toString());
            }
        }
        Collection c = fetchEmbed(link);
        if(c.items().isEmpty())
            throw new IOException("Spotify returned no tracks for this link (it may be private or region locked).");
        return c;
    }

    // ---- embed page strategy (no credentials) ----

    private Collection fetchEmbed(Link link) throws IOException
    {
        String html = get("https://open.spotify.com/embed/" + link.type() + "/" + link.id(), null);
        Collection c = parseEmbed(html, maxTracks);
        if(c == null)
            throw new IOException("Could not read Spotify metadata (the page format may have changed).");
        return c;
    }

    /** Parses the Next.js data blob of a Spotify embed page. Package-private for tests. */
    static Collection parseEmbed(String html, int max)
    {
        Matcher m = NEXT_DATA.matcher(html);
        if(!m.find())
            return null;
        try
        {
            JSONObject entity = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps")
                    .getJSONObject("state").getJSONObject("data").getJSONObject("entity");
            String type = entity.optString("type", "track");
            String name = entity.optString("name", entity.optString("title", "Spotify"));
            String art = null;
            JSONObject cover = entity.optJSONObject("coverArt");
            if(cover != null && cover.optJSONArray("sources") != null && !cover.getJSONArray("sources").isEmpty())
                art = cover.getJSONArray("sources").getJSONObject(0).optString("url", null);
            JSONObject vis = entity.optJSONObject("visualIdentity");
            if(art == null && vis != null && vis.optJSONArray("image") != null)
                art = bestImage(vis.getJSONArray("image"));
            List<Item> items = new ArrayList<>();
            if(type.equals("track"))
            {
                items.add(new Item(name, joinArtists(entity.optJSONArray("artists")), entity.optLong("duration", 0)));
            }
            else
            {
                JSONArray list = entity.optJSONArray("trackList");
                if(list != null)
                    for(int i = 0; i < list.length() && items.size() < max; i++)
                    {
                        JSONObject t = list.getJSONObject(i);
                        String title = t.optString("title", "");
                        if(!title.isBlank())
                            items.add(new Item(title, t.optString("subtitle", ""), t.optLong("duration", 0)));
                    }
            }
            return new Collection(type, name, art, items);
        }
        catch(JSONException e)
        {
            return null;
        }
    }

    private static String bestImage(JSONArray images)
    {
        String best = null;
        int bestW = -1;
        for(int i = 0; i < images.length(); i++)
        {
            JSONObject o = images.getJSONObject(i);
            if(o.optInt("maxWidth", 0) > bestW)
            {
                bestW = o.optInt("maxWidth", 0);
                best = o.optString("url", null);
            }
        }
        return best;
    }

    private static String joinArtists(JSONArray artists)
    {
        if(artists == null)
            return "";
        List<String> names = new ArrayList<>();
        for(int i = 0; i < artists.length(); i++)
            names.add(artists.getJSONObject(i).optString("name", ""));
        return String.join(", ", names);
    }

    // ---- official API strategy ----

    private Collection fetchApi(Link link) throws IOException
    {
        String auth = "Bearer " + accessToken();
        String base = "https://api.spotify.com/v1/";
        switch(link.type())
        {
            case "track":
            {
                JSONObject t = new JSONObject(get(base + "tracks/" + link.id(), auth));
                return new Collection("track", t.getString("name"), firstImage(t.optJSONObject("album")),
                        List.of(apiItem(t)));
            }
            case "album":
            {
                JSONObject a = new JSONObject(get(base + "albums/" + link.id(), auth));
                List<Item> items = new ArrayList<>();
                JSONObject page = a.getJSONObject("tracks");
                while(page != null && items.size() < maxTracks)
                {
                    JSONArray arr = page.getJSONArray("items");
                    for(int i = 0; i < arr.length() && items.size() < maxTracks; i++)
                        items.add(apiItem(arr.getJSONObject(i)));
                    String next = page.optString("next", null);
                    page = next == null || next.equals("null") ? null : new JSONObject(get(next, auth));
                }
                return new Collection("album", a.getString("name"), firstImage(a), items);
            }
            default: // playlist
            {
                JSONObject p = new JSONObject(get(base + "playlists/" + link.id() + "?fields=name,images", auth));
                List<Item> items = new ArrayList<>();
                int offset = 0;
                while(items.size() < maxTracks)
                {
                    // /items replaced /tracks in the 2026 API migration; entries may carry "item" or "track"
                    JSONObject page = new JSONObject(get(base + "playlists/" + link.id() + "/items?limit=100&offset=" + offset, auth));
                    JSONArray arr = page.optJSONArray("items");
                    if(arr == null || arr.isEmpty())
                        break;
                    for(int i = 0; i < arr.length() && items.size() < maxTracks; i++)
                    {
                        JSONObject e = arr.getJSONObject(i);
                        JSONObject t = e.optJSONObject("item") != null ? e.optJSONObject("item") : e.optJSONObject("track");
                        if(t != null && t.optString("name", "").length() > 0)
                            items.add(apiItem(t));
                    }
                    if(page.optString("next", "null").equals("null"))
                        break;
                    offset += 100;
                }
                return new Collection("playlist", p.getString("name"), firstImage(p), items);
            }
        }
    }

    private static Item apiItem(JSONObject t)
    {
        return new Item(t.getString("name"), joinArtists(t.optJSONArray("artists")), t.optLong("duration_ms", 0));
    }

    private static String firstImage(JSONObject o)
    {
        if(o == null || o.optJSONArray("images") == null || o.getJSONArray("images").isEmpty())
            return null;
        return o.getJSONArray("images").getJSONObject(0).optString("url", null);
    }

    private synchronized String accessToken() throws IOException
    {
        if(token != null && System.currentTimeMillis() < tokenExpiresAt - 30_000)
            return token;
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)))
                .build();
        try
        {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if(res.statusCode() != 200)
                throw new IOException("Spotify token request failed with HTTP " + res.statusCode());
            JSONObject o = new JSONObject(res.body());
            token = o.getString("access_token");
            tokenExpiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000;
            return token;
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private String get(String url, String authorization) throws IOException
    {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (compatible; JMusicBot)").GET();
        if(authorization != null)
            b.header("Authorization", authorization);
        try
        {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if(res.statusCode() != 200)
                throw new IOException("Spotify returned HTTP " + res.statusCode());
            return res.body();
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
