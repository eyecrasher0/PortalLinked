// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.entity.Player;

/** 1.12 has no PDC. One owned scoreboard tag lives in the player's native NBT. */
final class LegacyPlayerData {
    private static final String PREFIX = "portallinked:last_crossing:";
    private LegacyPlayerData() {}

    static byte[] read(Player player) {
        List<String> owned = owned(player);
        if (owned.size() != 1) return null;
        try {
            return Base64.getUrlDecoder().decode(owned.get(0).substring(PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static boolean write(Player player, byte[] value) {
        String replacement = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        List<String> previous = owned(player);
        if (previous.contains(replacement)) {
            for (String tag : previous) if (!tag.equals(replacement)) player.removeScoreboardTag(tag);
            return true;
        }
        // At the native 1024-tag limit, reuse a slot already owned by PortalLinked.
        String removed = null;
        if (player.getScoreboardTags().size() >= 1024 && !previous.isEmpty()) {
            removed = previous.get(0);
            player.removeScoreboardTag(removed);
        }
        if (!player.addScoreboardTag(replacement)) {
            if (removed != null) player.addScoreboardTag(removed);
            return false;
        }
        for (String tag : previous) player.removeScoreboardTag(tag);
        return true;
    }

    static void clear(Player player) {
        for (String tag : owned(player)) player.removeScoreboardTag(tag);
    }

    private static List<String> owned(Player player) {
        List<String> result = new ArrayList<>();
        for (String tag : player.getScoreboardTags()) if (tag.startsWith(PREFIX)) result.add(tag);
        return result;
    }
}
