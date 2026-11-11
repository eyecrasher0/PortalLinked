# PortalLinked

![PortalLinked](branding/icon.png)

PortalLinked remembers each player's portal pair, returning them to their original
portal when they retrace a Nether trip. New routes retain vanilla 1:8 scaling
and portal generation.

**Author:** EyeCrasher · **Platform:** Paper 26.3 · **Java:** 25 ·
**License:** GPL-3.0-only

## Behavior

- Remembers each player's last successful Overworld–Nether portal crossing.
- Returning through the same arrival portal leads to that player's original
  departure portal. Players sharing a Nether portal can return to different bases.
- Recognizes the entire portal frame, including both axes and all standard
  interior sizes from 2×3 to 21×21 blocks.
- Other portals use Minecraft's normal portal lookup, coordinate scaling,
  world border and exit generation.
- If the original portal is extinguished, destroyed, resized or outside the
  current world border, Minecraft's normal portal lookup is used.
- Saves the pair in the player's PersistentDataContainer, alongside normal
  player saves, logout and orderly server shutdown. The first crossing after
  installation establishes a pair.
- Cancelled or failed crossings do not replace a valid saved pair. Other
  plugins can cancel or redirect portal transitions.
- End portals, mobs and items keep their existing behavior.
- Uses server Player events and UUIDs, including players connected through
  Geyser. No client mod, commands, permissions or configuration are required.

## Installation

Stop the server, place `portallinked-paper-1.0.0+26.3.jar` in `plugins/`, then start
the server. Replace any earlier development build rather than loading both.
To uninstall, stop the server, remove the JAR and start it again.

## Build

Use JDK 25 and the included Gradle wrapper:

```sh
cd paper/26.3
./gradlew clean build
python3 tools/integration.py --accept-eula
```

The plugin JAR is generated at `paper/26.3/build/libs/portallinked-paper-1.0.0+26.3.jar`.
On Windows, use `gradlew.bat clean build`. Each platform/version directory is
a standalone project. Paper is a compile-only dependency and is not bundled
with the plugin. The integration command starts disposable local test servers
and requires accepting the Minecraft EULA with `--accept-eula`.

Paper plugins have no standard Mod Menu icon field. The supplied icon is included
in the JAR and used on the project pages.

The test suite covers routing and persisted-data validation. The release was
also tested using native player and portal transitions on Paper 26.3 build 244,
including a full shutdown/restart. No external Bedrock clients were connected
during those checks.

## License

Copyright (C) 2026 EyeCrasher. Licensed under the **GNU General Public License,
version 3 only** (`GPL-3.0-only`). See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Distribute the corresponding source archive alongside the plugin JAR.

Implemented independently using the public Paper API.
API references: [PlayerPortalEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerPortalEvent.html),
[PlayerChangedWorldEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerChangedWorldEvent.html),
[PersistentDataContainer](https://jd.papermc.io/paper/26.3/org/bukkit/persistence/PersistentDataContainer.html).
