// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ConfigTracker;

@Mod("portallinked_integration")
public final class FixtureForge {
    public FixtureForge() {
        // The disposable dedicated JVM has no client shutdown hook to release
        // Forge59.0.5's common-config watcher. Use the loader's public lifecycle.
        ServerStoppedEvent.BUS.addListener(event -> {
            ConfigTracker.INSTANCE.forceUnload();
            try {
                Files.writeString(Path.of("fixture-post-stop.txt"), System.getProperty("portallinked.phase", "1"));
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        });
    }
}
