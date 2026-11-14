# Changelog

## 1.2.0 — Backports (unreleased)

- Adds exact-version server builds for 1.7.10, 1.8.9, 1.12.2 and every stable
  Minecraft release from 1.16.5 through 26.2. The published 26.3 release remains
  available as 1.1.0. See [the exact platform matrix](tools/backport-targets.json).
- Includes Paper, Spigot, Purpur, Folia, Fabric, NeoForge and Forge where an
  official SDK exists for that exact Minecraft release.
- Preserves personal portal returns, native new routes and exit creation,
  protection events, whole-frame identities and administrator settings.
- Targets Java 8 for 1.7.10, 1.8.9, 1.12.2 and 1.16.5; Java 16 for 1.17.x;
  later releases use their recorded Java 17, 21 or 25 runtime.
- Stores Bukkit 1.12.2 links in one owned scoreboard tag in normal player NBT;
  newer Bukkit builds retain PDC storage. No external database is needed.
- Verifies every implemented server with real native transitions, player saves
  and complete process restarts. Historical SDKs are pinned to exact versions
  and checksums. Complete mapping files and server binaries are not distributed.

## 1.1.0 — Minecraft 26.3

- Adds Spigot, Purpur and Folia support in a single Bukkit plugin JAR.
- Adds server-only Fabric, NeoForge and Forge mod JARs; Fabric API is not required.
- Keeps Bukkit 1.0.0 saved links and configuration compatible.
- Adds a Folia async return handler with region-owned portal checks, entity-owned
  player data updates, cancellation and vanilla fallback.
- Preserves late portal-entry cancellations, exact adjacent source frames,
  native player cooldowns and Nether-entry restrictions on Folia.
- Captures Folia arrivals before player movement and invalidates pending returns
  atomically when administrators reload settings.
- Saves completed Folia journeys before logout, respects later entry cancellations,
  and retains native portal effects and Nether travel advancement tracking.
- Handles adjacent arrival frames and scaled player contact bounds.
- Uses the native first departure contact and preserves Folia's delay after exit
  and re-entry.
- Keeps swept-motion crossings available; Folia uses native routing when the
  accepted movement has already left the portal volume.
- Checks current player collision bounds before forcing a remembered return and
  runs native teleport callbacks outside the shared settings lock.
- Shares mod player-save data and JSON configuration across the three loaders.
- Rejects incomplete dimension identifiers in mod settings and saved portal pairs.
- Adds native server/restart verification for every supported platform.
- Documents admin settings, installation choices and source/build requirements.

## 1.0.0 — 2026-11-11

- Reject incomplete or invalid configuration; see [administration](README.md#administration).
- Initial PortalLinked release for Paper 26.3 and Java 25.
- Player-specific return links for Overworld–Nether portal trips.
- Vanilla portal lookup and creation for new routes and missing return portals.
- Vanilla fallback when Paper's live Nether ceiling policy excludes a saved portal.
- Persistent links across player saves, logout and orderly server restarts.
- Optional enabled, excluded-worlds and debug settings with admin-only
  `/portallinked reload`, `status` and `forget [online-player]` commands.
- Copyright EyeCrasher; licensed under GPL-3.0-only.
