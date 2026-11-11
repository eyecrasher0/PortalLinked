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
  current world border, Minecraft's normal portal lookup is used. The same
  applies when Paper's live Nether ceiling policy excludes the saved portal.
- Saves the pair in the player's PersistentDataContainer, alongside normal
  player saves, logout and orderly server shutdown. The first crossing after
  installation establishes a pair.
- Cancelled or failed crossings do not replace a valid saved pair. Other
  plugins can cancel or redirect portal transitions.
- End portals, mobs and items keep their existing behavior.
- Uses server Player events and UUIDs, including players connected through
  Geyser. No client mod or player setup is required; defaults work immediately.

## Administration

PortalLinked creates `plugins/PortalLinked/config.yml` on first start:

```yaml
enabled: true
excluded-worlds: []
debug: false
```

Set `enabled: false` to use vanilla portal travel without recording new links.
`excluded-worlds` accepts exact, case-sensitive world names, such as
`[world_nether]`. If either endpoint is excluded, both routing and recording
are skipped in both directions. Existing saved links are retained.
`debug: true` logs saved links, remembered returns and unavailable return portals
in English. It is off by default.

Apply configuration changes with `/portallinked reload`. The optional admin
commands require `portallinked.admin`, granted to operators by default:

- `/portallinked reload` reads the configuration from disk.
- `/portallinked status` shows the active settings.
- `/portallinked forget [online-player]` clears only that player's saved pair.
  Omit the name to clear your own pair. The console must supply an exact online
  player name. Their next crossing uses vanilla lookup and establishes a new pair.

Reloading clears pending crossings. Forgetting also clears the selected player's
pending crossing, so an arrival already in progress cannot recreate that link.
If YAML syntax or a setting's type is invalid, reload reports failure and retains
the previous active settings. Fix the file and reload again. If the initial
configuration is invalid, linking stays disabled until a valid reload succeeds.

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

The test suite covers routing and persisted-data validation. The native fixture
uses player and portal transitions on Paper 26.3 build 244, including a full
shutdown/restart, live Nether ceiling changes, configuration reload, admin
permissions, invalid configuration and forgetting individual player links.
No external Bedrock clients were connected during the release checks.

## License

Copyright (C) 2026 EyeCrasher. Licensed under the **GNU General Public License,
version 3 only** (`GPL-3.0-only`). See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Distribute the corresponding source archive alongside the plugin JAR.

Implemented independently using the Paper API and Paper's live per-world
configuration for portal eligibility.
API references: [PlayerPortalEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerPortalEvent.html),
[PlayerChangedWorldEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerChangedWorldEvent.html),
[PersistentDataContainer](https://jd.papermc.io/paper/26.3/org/bukkit/persistence/PersistentDataContainer.html).
