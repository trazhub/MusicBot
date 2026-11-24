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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HealthServerTest
{
    private HttpResponse<String> get(int port, String path) throws Exception
    {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reportsDownUntilGatewayConnected() throws Exception
    {
        HealthServer s = new HealthServer();
        s.start(() -> null, 0);
        try
        {
            HttpResponse<String> res = get(s.getPort(), "/healthz");
            assertEquals(503, res.statusCode());
            assertTrue(res.body().contains("\"status\":\"DOWN\""));
            assertEquals(1, HealthServer.check(s.getPort()), "healthcheck CLI must fail while down");
            assertEquals(404, get(s.getPort(), "/secrets").statusCode());
        }
        finally
        {
            s.stop();
        }
    }

    @Test
    void reportsUpWhenConnected() throws Exception
    {
        JDA jda = Mockito.mock(JDA.class);
        Mockito.when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        Mockito.when(jda.getGuildCache()).thenReturn(new net.dv8tion.jda.internal.utils.cache.SnowflakeCacheViewImpl<>(
                net.dv8tion.jda.api.entities.Guild.class, net.dv8tion.jda.api.entities.Guild::getName));
        HealthServer s = new HealthServer();
        s.start(() -> jda, 0);
        try
        {
            HttpResponse<String> res = get(s.getPort(), "/healthz");
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"status\":\"UP\""));
            assertEquals(0, HealthServer.check(s.getPort()));
        }
        finally
        {
            s.stop();
        }
    }
}
