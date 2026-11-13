# Backport targets

The requested scope is **1.8.9**, **1.12.2**, and every stable Java release from
**1.16.5 through 26.3**, including intermediate patch releases. These are exact
Minecraft targets, not broad compatibility ranges. The request for `1.7.0` is
awaiting clarification: Mojang has no Java release with that identifier.

The complete queue and upstream platform availability are recorded in
[`tools/backport-targets.json`](tools/backport-targets.json). Availability means
an official SDK or server exists; `planned` entries are not supported builds of
PortalLinked. Only **26.3 / PortalLinked 1.1.0** is released at present.
Work on **26.2 / PortalLinked 1.2.0** is in progress.

Folia 26.2 build7 has an upstream limitation: its native asynchronous Nether
travel path does not enforce `allowEnteringNetherUsingPortals`. PortalLinked
declines personal routing and link updates when that native predicate denies
entry, while the server's own route retains its existing behavior. The test
suite compares a direct native control with the plugin-enabled journey.

Some releases have no corresponding build of a particular server or loader.
For example, Forge publishes 1.8.9, but the official Paper and Spigot historical
servers use 1.8.8. The queue does not label a 1.8.8 server as a 1.8.9 server.
Folia and NeoForge are included only where those projects publish a build for
the requested release. No snapshot or neighbouring Minecraft version substitutes
for an exact target.

Inspect the queue:

```sh
python3 tools/backports.py list
python3 tools/backports.py list --minecraft 26.2 --json
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
