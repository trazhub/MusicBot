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

import java.net.InetAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlGuardTest
{
    @AfterEach
    void reset()
    {
        UrlGuard.setAllowPrivate(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/stream", "http://localhost:8080/x", "https://[::1]/x", "http://10.0.0.5/a", "http://192.168.1.1/",
        "http://172.16.0.1/", "http://172.31.255.255/", "http://169.254.169.254/latest/meta-data/", "http://0.0.0.0/",
        "http://100.64.0.1/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.1.2.3]/",
        "http://metadata.internal/", "http://printer.local/", "http://user:pass@93.184.216.34/", "ftp://93.184.216.34/file",
        "file:///etc/passwd", "javascript:alert(1)", "http:///nohost", "not a url", ""})
    void rejectsUnsafeUrls(String url)
    {
        assertThrows(UrlGuard.UnsafeUrlException.class, () -> UrlGuard.check(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://93.184.216.34/audio.mp3", "https://8.8.8.8/", "http://[2001:4860:4860::8888]/"})
    void acceptsPublicAddresses(String url)
    {
        assertDoesNotThrow(() -> UrlGuard.check(url));
    }

    @Test
    void allowPrivateOverridesChecks() throws Exception
    {
        UrlGuard.setAllowPrivate(true);
        assertNotNull(UrlGuard.check("http://192.168.1.10:8000/radio"));
    }

    @Test
    void classifiesAddresses() throws Exception
    {
        assertTrue(UrlGuard.isPrivate(InetAddress.getByName("172.20.1.1")));
        assertFalse(UrlGuard.isPrivate(InetAddress.getByName("172.32.0.1")));
        assertFalse(UrlGuard.isPrivate(InetAddress.getByName("1.1.1.1")));
        assertTrue(UrlGuard.isPrivate(InetAddress.getByName("198.18.0.1")));
    }
}
