// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.ResourceLocation;

final class Settings {
    final boolean enabled, debug;
    final Set<String> excluded;
    Settings(boolean enabled, Set<String> excluded, boolean debug) {
        this.enabled = enabled; this.excluded = Collections.unmodifiableSet(new HashSet<>(excluded)); this.debug = debug;
    }
    boolean allows(NativePortal portal) { return enabled && portal != null && !excluded.contains(portal.world); }

    static Settings read(Reader input) throws IOException {
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);
        Boolean enabled = null, debug = null;
        Set<String> excluded = null, fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw new IllegalArgumentException("Duplicate configuration field");
            if (field.equals("enabled") || field.equals("debug")) {
                if (reader.peek() != JsonToken.BOOLEAN) throw new IllegalArgumentException("Expected boolean");
                boolean value = reader.nextBoolean();
                if (field.equals("enabled")) enabled = value; else debug = value;
            } else if (field.equals("excluded-worlds")) {
                excluded = new HashSet<>(); reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException("Expected dimension name");
                    String name = reader.nextString();
                    if (!name.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                        throw new IllegalArgumentException("Use complete dimension identifiers");
                    // Construct the native identifier too, rather than accepting a value the SDK rejects.
                    new ResourceLocation(name);
                    excluded.add(name);
                }
                reader.endArray();
            } else reader.skipValue();
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT || enabled == null || debug == null || excluded == null)
            throw new IllegalArgumentException("Required configuration fields");
        return new Settings(enabled, excluded, debug);
    }
}
