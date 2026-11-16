# PortalLinked

[![GitHub release](https://img.shields.io/github/v/release/eyecrasher0/PortalLinked?label=release)](https://github.com/eyecrasher0/PortalLinked/releases)
[![Modrinth](https://img.shields.io/modrinth/dt/portallinked?label=modrinth)](https://modrinth.com/plugin/portallinked)
[![CurseForge Mods](https://img.shields.io/curseforge/dt/1797635?label=curseforge%20mods)](https://www.curseforge.com/projects/1797635)
[![CurseForge Plugins](https://img.shields.io/curseforge/dt/1797384?label=curseforge%20plugins)](https://www.curseforge.com/projects/1797384)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](https://github.com/eyecrasher0/PortalLinked/blob/main/LICENSE)

![PortalLinked](branding/icon.png)

PortalLinked remembers each player's Nether portal pair. Return through the same portal to reach the base you left, while new routes keep normal Minecraft portal search, generation and 1:8 scaling.

Players sharing a Nether portal can return to their own bases. Destroyed, extinguished, resized, blocked or excluded remembered exits use normal native routing. End portals, mobs and items retain their usual behavior.

## Choose your server build

Select the **exact Minecraft version and server/loader shown in the filename**.

| Server | File | Install in |
| --- | --- | --- |
| Paper, Spigot, Purpur, Folia | `portallinked-bukkit-<version>+<minecraft>.jar` | `plugins/` |
| Fabric | `portallinked-fabric-<version>+<minecraft>.jar` | `mods/` |
| NeoForge | `portallinked-neoforge-<version>+<minecraft>.jar` | `mods/` |
| Forge | `portallinked-forge-<version>+<minecraft>.jar` | `mods/` |

Exact builds cover **1.7.10**, **1.8.9**, **1.12.2**, and every stable release from **1.16.5 through 26.3**. Platforms vary by Minecraft version: see the [exact support matrix](https://github.com/eyecrasher0/PortalLinked/blob/portallinked-1.2.0/tools/backport-targets.json). A listed Minecraft family does not mean every platform exists for every patch.

Install one matching PortalLinked file on the server and replace older copies. PortalLinked itself is not required on clients, and Fabric API is not required. Forge 1.7.10 needs a matching Forge/FML client for its server login handshake. The 26.3 mods retain version **1.1.0**; the corrected Bukkit 26.3 build and the backports use **1.2.0**.

## Administrator settings

The Bukkit plugin creates `plugins/PortalLinked/config.yml`:

```yaml
enabled: true
excluded-worlds: []
debug: false
```

Fabric, NeoForge and Forge create `config/portallinked.json`:

```json
{
  "enabled": true,
  "excluded-worlds": [],
  "debug": false
}
```

Keep all three settings with the types shown. `enabled: false` preserves saved pairs while using normal portal travel. Excluded Bukkit worlds use exact world names; mods use complete dimension identifiers such as `minecraft:the_nether`. If either endpoint is excluded, both personal routing and recording are skipped. `debug: true` enables diagnostic logging.

- `/portallinked reload` applies a valid configuration.
- `/portallinked status` shows active settings.
- `/portallinked forget [online-player]` clears that player's remembered pair; omit the name for yourself.

Bukkit commands require `portallinked.admin`, granted to operators by default. Mod commands require operator level 2. Invalid reloads retain the previous active settings and saved pairs. Invalid startup configuration keeps linking disabled until a valid reload.

Links are stored with normal player data and survive player saves, logout, respawn and normal server restarts. No external database is required. A successful crossing establishes a fresh pair. Storage layouts are not automatically converted when changing between Bukkit, legacy Forge and modern mod adapters.

## Forge 1.8.9 observation

One manual return after a server restart reached a neighboring portal despite a saved pair, alongside an 80-block native movement rollback. Later manual and network-controlled repeats returned correctly, but the original cause remains unestablished.

Author: **EyeCrasher**. License: **GPL-3.0-only**. [Source code](https://github.com/eyecrasher0/PortalLinked) · [Issue tracker](https://github.com/eyecrasher0/PortalLinked/issues).

## Build

Each platform/version directory has its own wrapper and matching JDK. See
[BACKPORTS.md](BACKPORTS.md) for the exact target list and historical build requirements.
The commands below target Minecraft 26.3 with JDK 25:

```sh
cd bukkit/26.3
./gradlew clean build
python3 tools/integration.py --accept-eula
```

Use `--engine spigot`, `--engine purpur` or `--engine folia` for the other plugin
servers. The Spigot check builds its server with official BuildTools and requires
Git and network access. SDK download checksums/source revisions are pinned.

For a mod, run `./gradlew clean build integrationFixture` in `fabric/26.3`,
`neoforge/26.3` or `forge/26.3`, then from the repository root run:

```sh
python3 tools/mod-integration.py --loader fabric --accept-eula
```

Replace `fabric` with the matching loader. Each platform's runtime JAR is in its
module's `build/libs/` directory. Integration fixture JARs are disposable test
tools and must not be distributed or installed on a real server.

On Windows use `gradlew.bat`; the native integration runners target Linux.
Each platform/version directory has its own Gradle project and wrapper. Build
from a complete repository checkout: mod projects include `mod-common/26.3` and
the root branding resources. Platform APIs and Minecraft are not bundled in
runtime JARs. Integration starts disposable, loopback-only test servers and
requires accepting the Minecraft EULA with `--accept-eula`.

Paper plugins have no standard Mod Menu icon field. The supplied icon is included
in the JAR and used on the project pages.

The test suites cover routing, persisted data, native transitions, actual
shutdown/restart, configuration reload, permissions, invalid settings and
individual link resets. Bukkit checks include live Paper ceiling changes where
the server supports that policy. Folia checks run across region schedulers;
the mod checks also cover native End, mob/item and respawn behavior.
The automated fixtures exercise native server behavior. Representative manual
client checks were performed separately; the suite does not establish every
external client or protocol combination.

## License

Copyright (C) 2026 EyeCrasher. Licensed under the **GNU General Public License,
version 3 only** (`GPL-3.0-only`). See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Distribute the complete corresponding source archive alongside runtime JARs.

Implemented independently using the Bukkit/Paper APIs, loader SDKs and native
Minecraft portal transitions. Paper's live per-world configuration is respected
when the server provides it.
API references: [PlayerPortalEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerPortalEvent.html),
[PlayerChangedWorldEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerChangedWorldEvent.html),
[PersistentDataContainer](https://jd.papermc.io/paper/26.3/org/bukkit/persistence/PersistentDataContainer.html).
