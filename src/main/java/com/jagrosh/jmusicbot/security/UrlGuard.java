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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF protection. User supplied URLs are only fetched if they are http(s) and every address the
 * host resolves to is a public, routable address. The same address check is installed as a DNS
 * resolver on the HTTP client used for generic URLs, so redirects and DNS rebinding are covered too.
 */
public final class UrlGuard
{
    private static volatile boolean allowPrivate = false;

    private UrlGuard() {}

    public static void setAllowPrivate(boolean allow)
    {
        allowPrivate = allow;
    }

    public static boolean isAllowPrivate()
    {
        return allowPrivate;
    }

    public static class UnsafeUrlException extends Exception
    {
        public UnsafeUrlException(String message)
        {
            super(message);
        }
    }

    /** Validates a user supplied URL. Throws if it must not be fetched. */
    public static URI check(String url) throws UnsafeUrlException
    {
        URI uri;
        try
        {
            uri = new URI(url.trim());
        }
        catch(Exception e)
        {
            throw new UnsafeUrlException("Malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if(!scheme.equals("http") && !scheme.equals("https"))
            throw new UnsafeUrlException("Only http and https links are allowed");
        if(uri.getRawUserInfo() != null)
            throw new UnsafeUrlException("Links with embedded credentials are not allowed");
        String host = uri.getHost();
        if(host == null || host.isEmpty())
            throw new UnsafeUrlException("Link has no host");
        if(allowPrivate)
            return uri;
        String lower = host.toLowerCase(Locale.ROOT);
        if(lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local") || lower.endsWith(".internal"))
            throw new UnsafeUrlException("Link points to a private address");
        InetAddress[] addrs;
        try
        {
            addrs = InetAddress.getAllByName(host);
        }
        catch(UnknownHostException e)
        {
            throw new UnsafeUrlException("Host could not be resolved");
        }
        for(InetAddress a : addrs)
            if(isPrivate(a))
                throw new UnsafeUrlException("Link points to a private address");
        return uri;
    }

    /** True if the address is loopback, private, link-local, multicast, carrier-grade NAT, or otherwise non-public. */
    public static boolean isPrivate(InetAddress a)
    {
        if(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return true;
        byte[] b = a.getAddress();
        if(b.length == 4)
        {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if(b0 == 0) return true;                                      // 0.0.0.0/8
            if(b0 == 100 && b1 >= 64 && b1 <= 127) return true;           // 100.64.0.0/10 CGNAT
            if(b0 == 169 && b1 == 254) return true;                       // link-local / cloud metadata
            if(b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return true;   // 192.0.0.0/24
            if(b0 == 198 && (b1 == 18 || b1 == 19)) return true;          // benchmarking
            return b0 >= 240;                                             // reserved / broadcast
        }
        if(b.length == 16)
        {
            if((b[0] & 0xfe) == 0xfc) return true;                        // fc00::/7 unique local
            boolean mapped = true;                                        // ::ffff:a.b.c.d
            for(int i = 0; i < 10; i++)
                if(b[i] != 0) { mapped = false; break; }
            if(mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff)
            {
                try
                {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                }
                catch(UnknownHostException e)
                {
                    return true;
                }
            }
        }
        return false;
    }
}
