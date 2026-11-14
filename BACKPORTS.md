# Backport targets

The requested scope is **1.7.10**, **1.8.9**, **1.12.2**, and every stable Java release from
**1.16.5 through 26.3**, including intermediate patch releases. These are exact
Minecraft targets, not broad compatibility ranges. The owner clarified the original `1.7.0` request as **1.7.10**.
The resulting queue contains **38** exact Minecraft releases.

The complete queue and upstream platform availability are recorded in
[`tools/backport-targets.json`](tools/backport-targets.json). Availability means
an official SDK or server exists; `planned` entries are not supported builds of
PortalLinked. Only **26.3 / PortalLinked 1.1.0** is released at present.
All **PortalLinked 1.2.0** backport adapters have completed local native
verification. They remain unreleased until publication and CI verification.
The queue records native verification separately from released builds.

Folia 26.2 build7, 26.1.2 build8 and 1.21.11 build14 have an upstream limitation: its native asynchronous Nether
travel path does not enforce `allowEnteringNetherUsingPortals`. PortalLinked
declines personal routing and link updates when that native predicate denies
entry, while the server's own route retains its existing behavior. The test
suite compares a direct native control with the plugin-enabled journey.

Folia 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21.4, 1.21.5, 1.21.6 and 1.21.8 predate that game rule and use the server's `allow-nether`
startup setting. PortalLinked honours it for personal routing and link updates.
The native suite also compares behavior after a live setting change while the
Nether world is already loaded, without treating that as a supported server
configuration reload.

The 1.21 and 1.21.1 plugin adapters retain the last accepted portal contact,
matching those servers' native selection when a player overlaps neighboring
frames. These versions inspect the final entity bounds during fast movement;
the native tests confirm that crossing a frame without an accepted collision
does not create a journey or change an existing pair. Later SDKs have separate
swept-collision checks.

The Folia 1.21.5 fixture waits for native world initialization before creating
distant test regions. It does not alter the runtime plugin or server spawn
initialization.

Some releases have no corresponding build of a particular server or loader.
For example, Forge publishes 1.8.9, but the official Paper and Spigot historical
servers use 1.8.8. The queue does not label a 1.8.8 server as a 1.8.9 server.
Folia and NeoForge are included only where those projects publish a build for
the requested release. No snapshot or neighbouring Minecraft version substitutes
for an exact target. Spigot version aliases sometimes select a newer patch;
the queue pins historical numeric revisions in those cases. The native runner
checks the server JAR’s embedded Minecraft version before starting it.

Paper publishes no 1.21.2 API, so that Spigot adapter compiles against the shared
Bukkit API surface from Paper 1.21.3 and is tested on the exact Spigot 1.21.2
server. Similarly, the Spigot 26.1 adapter compiles against Paper's 26.1.1 API.
Paper 1.20.5's historical API POM references an unavailable Adventure snapshot BOM,
so its adapter compiles against Paper 1.20.6's shared API and runs on exact 1.20.5
servers. Spigot 1.20.3 similarly uses the shared API from Paper 1.20.4.
These compile dependencies do not add neighboring Minecraft server targets.

Inspect the queue:

```sh
python3 tools/backports.py list
python3 tools/backports.py list --minecraft 26.2 --json
python3 tools/backports.py ci-matrix
```

Use the appropriate JDK for a target, then build an implemented adapter and run
its disposable native server checks:

```sh
python3 tools/backports.py build --minecraft 26.2 --platform fabric
python3 tools/backports.py native --minecraft 26.2 --platform fabric --accept-eula
```

The Bukkit project builds one JAR for its available plugin servers. Each mod
loader has a separate runtime JAR. Each platform/version directory retains its
own Gradle wrapper; mod projects use the matching `mod-common/<minecraft>` source
and the shared root branding. Keep the complete checkout when building.

Every port must preserve personal return pairs, ordinary new-route generation,
strict admin settings, cancellation and player-save persistence. Native checks
must execute the matching Minecraft server and confirm save, shutdown and restart
before a target is marked verified. Target-specific test code adapts to the old
SDK rather than changing the runtime behavior to satisfy a fixture.

The backport launcher sets `sync-chunk-writes=false` only on its disposable test
servers to avoid host filesystem contention. Player saves, actual process exit,
relaunch and restored-data assertions remain mandatory. Direct native runners
also accept `--sync-chunk-writes true` for the server's normal write setting.

Some older Forge installers produce different archive checksums with newer JDKs.
The pinned Forge 26.1 and 1.21.11 installers validate successfully using JDK17.
Pass `--installer-java /path/to/jdk-17/bin/java` to the backport native command;
the server still uses the target Minecraft JDK. Installer checksum checks remain
enabled. ForgeGradle7 build tools need JDK25 even for a Java21 Minecraft target;
provide both toolchains to Gradle when building those adapters. Forge 1.19.4
through 1.20.4 instead use ForgeGradle6, Java17 build tools and production SRG
remapping, including separate runtime reference maps for the mod and fixture.

Spigot 1.21.11 uses Mojang’s official `1.21.11_unobfuscated` variant. Its SDK
definition pins that variant explicitly; other Minecraft patch releases remain
separate targets.

Legacy Forge 1.7.10, 1.8.9 and 1.12.2 builds use Java8. They store the structured
portal pair in Forge’s native `PlayerPersisted` player NBT, which survives death
and respawn; modern loader builds use their native player-save hook. No external
database is required. Changing between these legacy and modern save layouts does
not automatically migrate remembered pairs; an ordinary successful crossing
establishes a fresh pair.

Forge 1.21.9 / 59.0.5 leaves a NightConfig3.7.4 watcher thread alive after
shutdown, including on a server with no mods. The disposable Forge fixture
unloads the loader configuration context through `ConfigTracker.forceUnload()`
after `ServerStoppedEvent`, then records completion for that phase. Portal
checks, native player saves and a real process restart still run in full. This
cleanup is confined to the fixture and recorded in its SDK definition; the
PortalLinked runtime does not change the loader's shutdown behavior.

The Backport verification workflow builds every implemented target in the queue
and runs its native restart checks with the matching Minecraft Java runtime.
Planned adapters are omitted until their projects exist. Its artifacts retain
the built JARs, test reports and native server logs. The released 26.3 target
continues to use the separate Platform verification workflow.

Minecraft 1.20.4 Bukkit servers also predate Paper's Mojang runtime namespace.
The runner composes an exact BuildTools namespace map in its private SDK cache,
remaps Paper/Folia's patched SDK only for compiling the fixture, and remaps that
fixture back to the native server namespace. Only the reflection symbols used
by PortalLinked are packaged with the plugin; full SDK mappings are not shipped.
Before the vanilla scale attribute existed, enlarged-body checks use a native
ServerPlayer subclass overriding its actual dimensions. The same collision,
source contact, saved exit and fallback assertions still run.

Forge 1.20.3 uses its installer's native shim JAR rather than an argument file.
The runner follows that pinned entry point and rejects a successful installer
exit if the expected server launcher is missing. NeoForge 1.20.1 retains the
`net.neoforged:forge` artifact and Forge package namespace; its SDK is pinned
separately from later `net.neoforged:neoforge` releases.

The 1.19.4 plugin compiles against that release's Folia API, which includes the
shared Paper API plus region scheduler methods missing from the contemporary
Paper API. Versions without an official Folia server omit the Folia route
adapter. The 1.20 plugin still compiles its optional adapter using the shared
Paper 1.20.4 API; native verification always starts exact 1.20 servers.

The Bukkit 1.12.2 adapter predates the Persistent Data Container API. It stores
one versioned `portallinked:last_crossing:` scoreboard tag in the player's native
NBT. The payload contains the same UUID and whole-frame geometry as newer
Bukkit builds. It survives ordinary player saves, respawn and server restart;
there is no separate database. Other scoreboard tags remain untouched. At the
native 1024-tag limit an existing PortalLinked slot is reused; if all slots
belong to other features, travel stays native and no new link is saved.

Minecraft 1.16.5 runtime JARs target Java 8; 1.17 and 1.17.1 target Java 16.
Their build JDK can be newer, as recorded separately in the target manifest.
The legacy Bukkit native fixtures compose their development namespace from the
pinned official Minecraft mappings and Spigot SDK; complete mapping files and
server binaries are kept out of the repository and runtime JARs.

The 1.17 native fixture lets the server process queued entity-chunk loads before
its final synchronous world save. The player save, normal process shutdown and
restart assertions are retained. Paper 1.17's fixture also applies its custom
posed dimensions to the native test entity bounds after refreshing its pose;
this accommodates that engine's fixed player box recalculation without changing
PortalLinked or the server implementation.
