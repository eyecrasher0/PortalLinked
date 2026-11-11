# PortalLinked

[![GitHub release](https://img.shields.io/github/v/release/eyecrasher0/PortalLinked?label=release)](https://github.com/eyecrasher0/PortalLinked/releases)
[![Modrinth](https://img.shields.io/modrinth/dt/portallinked?label=modrinth)](https://modrinth.com/plugin/portallinked)
[![CurseForge Mods](https://img.shields.io/curseforge/dt/1797635?label=curseforge%20mods)](https://www.curseforge.com/projects/1797635)
[![CurseForge Plugins](https://img.shields.io/curseforge/dt/1797384?label=curseforge%20plugins)](https://www.curseforge.com/projects/1797384)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](https://github.com/eyecrasher0/PortalLinked/blob/main/LICENSE)

![PortalLinked](branding/icon.png)

PortalLinked remembers each player's portal pair, returning them to their original
portal when they retrace a Nether trip. New routes retain vanilla 1:8 scaling
and portal generation. Install one server plugin or mod; players need no client mod.

**Author:** EyeCrasher · **Minecraft:** 26.3 · **Java:** 25 ·
**License:** GPL-3.0-only

## Platforms

| Server | Install this file | Directory |
| --- | --- | --- |
| Paper, Spigot, Purpur, Folia | `portallinked-bukkit-1.1.0+26.3.jar` | `plugins/` |
| Fabric | `portallinked-fabric-1.1.0+26.3.jar` | `mods/` |
| NeoForge | `portallinked-neoforge-1.1.0+26.3.jar` | `mods/` |
| Forge | `portallinked-forge-1.1.0+26.3.jar` | `mods/` |

Use only the file for your server. Fabric API is not required. Minimum loader
versions are Fabric Loader 0.20.1, NeoForge 26.3.0.102 and Forge 66.1.5.
This release targets Minecraft 26.3; older Minecraft versions are not included.
Folia is verified against its 26.3 build 6 alpha and uses region/entity schedulers.

## Behavior

- Remembers each player's last successful Overworld–Nether portal crossing.
- Returning through the same arrival portal leads to that player's original
  departure portal. Players sharing a Nether portal can return to different bases.
- Recognizes the entire portal frame, including both axes and all standard
  interior sizes from 2×3 to 21×21 blocks.
- Other portals use Minecraft's normal portal lookup, coordinate scaling,
  world border and exit generation.
- If the original portal is extinguished, destroyed, resized or outside the
  current world border, Minecraft's normal portal lookup is used. The same
  applies when Paper's live Nether ceiling policy excludes the saved portal.
- Saves the pair alongside normal player saves, logout and orderly server
  shutdown. Plugins use the player's PersistentDataContainer; mods use player
  NBT. No external database is needed. The first crossing after installation
  establishes a pair. A crash before a player save can lose the latest change.
- Cancelled or failed crossings do not replace a valid saved pair. Other
  plugins can cancel or redirect portal transitions.
- End portals, mobs and items keep their existing behavior.
- Uses player UUIDs, including players connected through Geyser on a compatible
  server. No client mod or player setup is required; defaults work immediately.

On Folia, new trips retain the server's native asynchronous portal travel.
Remembered returns validate the destination on its owning region and move the
player with `teleportAsync`. They use a clear position in the original portal
and emit cancellable player portal/teleport events. If that position is blocked,
the server uses its native lookup. Mounted players retain native Folia routing.
Player data is changed only on the owning entity's scheduler.

## Administration

The plugin creates `plugins/PortalLinked/config.yml` on first start:

```yaml
enabled: true
excluded-worlds: []
debug: false
```

All three keys must remain present with valid types: `enabled` and `debug` must
be booleans, and `excluded-worlds` must be a list of non-empty world names.
Missing keys, null values and empty scalar values are invalid; `[]` is a valid
empty list of excluded worlds.

Set `enabled: false` to use vanilla portal travel without recording new links.
`excluded-worlds` accepts exact, case-sensitive world names, such as
`[world_nether]`. If either endpoint is excluded, both routing and recording
are skipped in both directions. Existing saved links are retained.
`debug: true` logs saved links, remembered returns and unavailable return portals
in English. It is off by default.

The Fabric, NeoForge and Forge mods create `config/portallinked.json`:

```json
{
  "enabled": true,
  "excluded-worlds": [],
  "debug": false
}
```

For mods, exclusions use complete, case-sensitive dimension identifiers, such as
`["minecraft:the_nether"]`. The settings have the same effect as the plugin
settings. Keep all three fields present with the types shown above.

Apply configuration changes with `/portallinked reload`. The optional admin
commands require `portallinked.admin` on Bukkit servers, granted to operators by
default. The mods require vanilla gamemaster command permission (operator level 2):

- `/portallinked reload` reads the configuration from disk.
- `/portallinked status` shows the active settings.
- `/portallinked forget [online-player]` clears only that player's saved pair.
  Omit the name to clear your own pair. The Bukkit console must supply an exact
  online player name; mods also accept vanilla single-player selectors. Their
  next crossing uses vanilla lookup and establishes a new pair.

Successful reloads clear pending crossings. Forgetting also clears the selected
player's pending crossing, so an arrival already in progress cannot recreate that link.
If a key is missing or null, YAML syntax is invalid, or a setting has the wrong
type, reload reports failure and retains the previous active settings, pending
crossings and saved links. Fix the file and reload again. If the initial
configuration is invalid, linking stays disabled until a valid reload succeeds.

## Installation

Stop the server, place the matching file from the platform table in `plugins/`
or `mods/`, then start the server. Replace earlier PortalLinked JARs instead of
loading multiple copies. To uninstall, stop the server, remove the JAR and start
it again. Bukkit 1.0.0 player links and configuration remain compatible with 1.1.0.
Mod saves are shared between Fabric, NeoForge and Forge; Bukkit player-link data
is not automatically converted when changing between a plugin and a mod server.

## Build

Use JDK 25 and the included Gradle wrapper:

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

Replace `fabric` with the matching loader. The normal runtime JAR is in that
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
No external Java or Bedrock clients were connected during release checks;
automated transitions do not establish end-to-end client compatibility.

## License

Copyright (C) 2026 EyeCrasher. Licensed under the **GNU General Public License,
version 3 only** (`GPL-3.0-only`). See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Distribute the corresponding source archive alongside the plugin JAR.

Implemented independently using the Bukkit/Paper APIs, loader SDKs and native
Minecraft portal transitions. Paper's live per-world configuration is respected
when the server provides it.
API references: [PlayerPortalEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerPortalEvent.html),
[PlayerChangedWorldEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerChangedWorldEvent.html),
[PersistentDataContainer](https://jd.papermc.io/paper/26.3/org/bukkit/persistence/PersistentDataContainer.html).
