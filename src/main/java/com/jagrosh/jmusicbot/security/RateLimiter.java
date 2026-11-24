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

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Sliding-window rate limiter. Every key has an independent budget (use "guild:user" keys). */
public class RateLimiter
{
    private final int maxEvents;
    private final long windowMs;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int maxEvents, long windowMs)
    {
        this(maxEvents, windowMs, System::currentTimeMillis);
    }

    public RateLimiter(int maxEvents, long windowMs, LongSupplier clock)
    {
        this.maxEvents = maxEvents;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** @return 0 if the action is allowed (and is recorded); otherwise milliseconds until the next allowed attempt. */
    public long tryAcquire(String key)
    {
        if(maxEvents <= 0)
            return 0;
        long now = clock.getAsLong();
        ArrayDeque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized(q)
        {
            while(!q.isEmpty() && now - q.peekFirst() >= windowMs)
                q.pollFirst();
            if(q.size() >= maxEvents)
                return windowMs - (now - q.peekFirst());
            q.addLast(now);
            return 0;
        }
    }

    /** Drops idle keys; call periodically. */
    public void cleanup()
    {
        long now = clock.getAsLong();
        hits.entrySet().removeIf(e ->
        {
            synchronized(e.getValue())
            {
                return e.getValue().isEmpty() || now - e.getValue().peekLast() >= windowMs;
            }
        });
    }
}
