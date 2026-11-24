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
package com.jagrosh.jmusicbot.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterAndSecretsTest
{
    @Test
    void allowsBurstThenBlocksThenRecovers()
    {
        AtomicLong now = new AtomicLong(1_000);
        RateLimiter rl = new RateLimiter(3, 10_000, now::get);
        for(int i = 0; i < 3; i++)
            assertEquals(0, rl.tryAcquire("g:u"));
        long wait = rl.tryAcquire("g:u");
        assertTrue(wait > 0 && wait <= 10_000);
        now.addAndGet(wait);
        assertEquals(0, rl.tryAcquire("g:u"));
    }

    @Test
    void keysAreIsolated()
    {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rl = new RateLimiter(1, 10_000, now::get);
        assertEquals(0, rl.tryAcquire("guildA:user"));
        assertTrue(rl.tryAcquire("guildA:user") > 0);
        assertEquals(0, rl.tryAcquire("guildB:user"), "another guild must have its own budget");
        assertEquals(0, rl.tryAcquire("guildA:other"));
    }

    @Test
    void zeroDisablesLimiting()
    {
        RateLimiter rl = new RateLimiter(0, 10_000);
        for(int i = 0; i < 100; i++)
            assertEquals(0, rl.tryAcquire("x"));
    }

    @Test
    void redactsRegisteredSecretsAndTokenShapes()
    {
        Secrets.register("super-secret-value-123");
        String out = Secrets.redact("failed with super-secret-value-123 in header");
        assertFalse(out.contains("super-secret-value-123"));
        // assembled at runtime so no token-shaped literal is committed (secret scanners would flag it)
        String token = String.join(".", "MTIzNDU2Nzg5MDEyMzQ1Njc4", "GabcDE", "abcdefghijklmnopqrstuvwxyz" + "0123456789");
        assertFalse(Secrets.redact("login " + token).contains(token));
        assertEquals("nothing to hide", Secrets.redact("nothing to hide"));
        assertNull(Secrets.redact(null));
    }
}
