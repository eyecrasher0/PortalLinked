# PortalLink

**PortalLink** is a server-side Paper plugin that remembers each player’s portal route between the Overworld and the Nether.

Enter the Nether from your base, then return through the same portal you arrived at, and you’ll come back to your original portal. Multiple players can share a Nether portal while keeping their own return destinations.

## Features

- **Personal return routes:** portal links are remembered separately for each player.
- **Normal Nether travel:** other portals keep Minecraft’s usual 1:8 coordinate scaling, portal search and exit generation.
- **Automatic fallback:** if your original portal is destroyed, extinguished, resized or outside the world border, Minecraft handles the trip normally.
- **Persistent links:** remembered routes survive logout and orderly server restarts.
- **Full portal recognition:** supports both orientations and standard portal sizes, even when you return through a different block of the same frame.

PortalLink affects player travel between the Overworld and the Nether. End portals, mobs and items retain their existing behavior.

## Requirements

- Paper **26.3**
- Java **25**

No client mod, additional dependencies or configuration are required.

## Installation

1. Stop your server.
2. Place the PortalLink JAR in the `plugins` folder.
3. Start the server.

Your first successful portal crossing establishes your return route.

## Author and license

Created by **EyeCrasher**. Licensed under **GPL-3.0-only**.
