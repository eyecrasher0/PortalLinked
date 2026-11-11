# Changelog

## 1.0.0 — 2026-11-11

- Invalid YAML, missing/null keys or wrong setting types retain active settings,
  pending crossings and saved links, and report reload failure.

- Initial PortalLinked release for Paper 26.3 and Java 25.
- Player-specific return links for Overworld–Nether portal trips.
- Vanilla portal lookup and creation for new routes and missing return portals.
- Vanilla fallback when Paper's live Nether ceiling policy excludes a saved portal.
- Persistent links across player saves, logout and orderly server restarts.
- Optional enabled, excluded-worlds and debug settings with admin-only
  `/portallinked reload`, `status` and `forget [online-player]` commands.
- Copyright EyeCrasher; licensed under GPL-3.0-only.
