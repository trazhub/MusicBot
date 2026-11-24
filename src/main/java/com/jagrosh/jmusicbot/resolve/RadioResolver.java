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
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finds internet radio stations by name using the community radio-browser.info directory. */
public class RadioResolver
{
    public record Station(String name, String streamUrl, String country, String codec, int bitrate, String favicon) {}

    private static final String[] HOSTS = {"all.api.radio-browser.info", "de1.api.radio-browser.info"};
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    public List<Station> search(String name, int limit) throws IOException
    {
        String path = "/json/stations/search?hidebroken=true&order=votes&reverse=true&limit=" + limit
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        IOException last = null;
        for(String host : HOSTS)
        {
            try
            {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + host + path)).timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "JMusicBot/0.5").GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if(res.statusCode() != 200)
                    throw new IOException("Radio directory returned HTTP " + res.statusCode());
                return parse(res.body());
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
            catch(IOException e)
            {
                last = e;
            }
        }
        throw last == null ? new IOException("Radio directory unavailable") : last;
    }

    static List<Station> parse(String json)
    {
        List<Station> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for(int i = 0; i < arr.length(); i++)
        {
            JSONObject o = arr.getJSONObject(i);
            String url = o.optString("url_resolved", o.optString("url", ""));
            if(url.isBlank() || o.optInt("hls", 0) == 1 && !url.startsWith("http"))
                continue;
            out.add(new Station(o.optString("name", "Unknown").trim(), url, o.optString("country", ""),
                    o.optString("codec", ""), o.optInt("bitrate", 0), o.optString("favicon", "")));
        }
        return out;
    }
}
