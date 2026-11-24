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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * Secret handling: values come from environment variables or Docker/K8s secret files
 * ({@code NAME_FILE=/run/secrets/x}), never from command lines. Registered secrets are
 * scrubbed from anything that is logged or shown to users.
 */
public final class Secrets
{
    private static final Set<String> KNOWN = new CopyOnWriteArraySet<>();
    private static final Pattern DISCORD_TOKEN = Pattern.compile("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}");

    private Secrets() {}

    /** Reads NAME, or the contents of the file named by NAME_FILE. Returns null if neither is set. */
    public static String env(String name)
    {
        String v = System.getenv(name);
        if(v != null && !v.isBlank())
            return v.trim();
        String file = System.getenv(name + "_FILE");
        if(file != null && !file.isBlank())
        {
            try
            {
                return Files.readString(Path.of(file.trim())).trim();
            }
            catch(Exception e)
            {
                throw new IllegalStateException("Could not read secret file for " + name + ": " + e.getMessage());
            }
        }
        return null;
    }

    public static void register(String secret)
    {
        if(secret != null && secret.length() >= 8)
            KNOWN.add(secret);
    }

    /** Removes every registered secret, and anything shaped like a Discord bot token, from the text. */
    public static String redact(String text)
    {
        if(text == null)
            return null;
        String out = text;
        for(String s : KNOWN)
            out = out.replace(s, "[REDACTED]");
        return DISCORD_TOKEN.matcher(out).replaceAll("[REDACTED]");
    }
}
