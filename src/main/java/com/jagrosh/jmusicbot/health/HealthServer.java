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
import java.io.IOException;
import java.net.InetAddress;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
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

    private ServerSocket server;
    private volatile boolean running;

    public void start(Bot bot, int port) throws IOException
    {
        start(bot::getJDA, port);
    }

    /** @param port TCP port on 127.0.0.1, or 0 for any free port (see {@link #getPort()}) */
    public void start(Supplier<JDA> jdaSupplier, int port) throws IOException
    {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        running = true;
        Thread t = new Thread(() ->
        {
            while(running)
            {
                try(Socket s = server.accept())
                {
                    s.setSoTimeout(2000);
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[512];
                    int n = in.read(buf); // request line is all we look at
                    String req = n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.US_ASCII);
                    boolean ok = req.startsWith("GET /healthz") || req.startsWith("GET / ");
                    JDA jda = jdaSupplier.get();
                    boolean up = jda != null && jda.getStatus() == JDA.Status.CONNECTED;
                    String body = ok ? "{\"status\":\"" + (up ? "UP" : "DOWN") + "\",\"gateway\":\"" + (jda == null ? "STARTING" : jda.getStatus().name())
                            + "\",\"guilds\":" + (jda == null ? 0 : jda.getGuildCache().size())
                            + ",\"dave\":" + daveAvailable + ",\"uptimeSeconds\":" + (System.currentTimeMillis() - STARTED) / 1000 + "}" : "{}";
                    int code = !ok ? 404 : up ? 200 : 503;
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : code == 404 ? " Not Found" : " Service Unavailable")
                            + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(bytes);
                    out.flush();
                }
                catch(IOException e)
                {
                    if(running)
                        LOG.debug("Health request failed: {}", e.toString());
                }
            }
        }, "jmusicbot-health");
        t.setDaemon(true);
        t.start();
        LOG.info("Health endpoint listening on http://127.0.0.1:{}/healthz", port);
    }

    public int getPort()
    {
        return server.getLocalPort();
    }

    public void stop()
    {
        running = false;
        try
        {
            if(server != null)
                server.close();
        }
        catch(IOException ignored) { /* closing */ }
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
        return check(port);
    }

    public static int check(int port)
    {
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
