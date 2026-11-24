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
package com.jagrosh.jmusicbot.health;

import com.jagrosh.jmusicbot.Bot;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal health endpoint on 127.0.0.1 only (never exposed to the network by itself):
 * {@code GET /healthz} returns 200 when the Discord gateway is connected, 503 otherwise.
 * It carries no secrets, only coarse status.
 */
public class HealthServer
{
    private static final Logger LOG = LoggerFactory.getLogger("Health");
    private static final long STARTED = System.currentTimeMillis();
    /** Set at startup: whether the DAVE (Discord E2EE voice) implementation loaded. */
    public static volatile boolean daveAvailable = false;

    private HttpServer server;

    public void start(Bot bot, int port) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/healthz", ex ->
        {
            JDA jda = bot.getJDA();
            boolean up = jda != null && jda.getStatus() == JDA.Status.CONNECTED;
            String body = "{\"status\":\"" + (up ? "UP" : "DOWN") + "\",\"gateway\":\"" + (jda == null ? "STARTING" : jda.getStatus().name())
                    + "\",\"guilds\":" + (jda == null ? 0 : jda.getGuildCache().size())
                    + ",\"dave\":" + daveAvailable + ",\"uptimeSeconds\":" + (System.currentTimeMillis() - STARTED) / 1000 + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(up ? 200 : 503, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        LOG.info("Health endpoint listening on http://127.0.0.1:{}/healthz", port);
    }

    public void stop()
    {
        if(server != null)
            server.stop(0);
    }

    /** CLI entry for Docker HEALTHCHECK: {@code java -jar JMusicBot.jar healthcheck}. Exit code 0 = healthy. */
    public static int check()
    {
        String p = System.getenv("JMUSICBOT_HEALTH_PORT");
        int port = 8080;
        try
        {
            if(p != null && !p.isBlank())
                port = Integer.parseInt(p.trim());
        }
        catch(NumberFormatException ignored) { /* default */ }
        try
        {
            HttpResponse<Void> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200 ? 0 : 1;
        }
        catch(Exception e)
        {
            return 1;
        }
    }
}
