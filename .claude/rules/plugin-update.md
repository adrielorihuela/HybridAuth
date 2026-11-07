---
paths:
  - "proxy/src/main/java/com/velocitypowered/proxy/plugin/update/**"
  - "proxy/src/test/java/com/velocitypowered/proxy/plugin/update/**"
---

<!-- Moved from CLAUDE.md § Architecture › Plugin auto-update on 2026-11-07 -->

### Plugin auto-update

`plugin/update/PluginUpdater` runs from `VelocityServer#start()` **immediately before
`loadPlugins()`** and blocks until every listed plugin is on disk — after that point the loader has
already read the directory. `reloadConfiguration()` deliberately does not repeat it: plugins are
not reloadable, so a newer jar there would only make the files disagree with what is running.

Each source is a `PluginSource` returning a `PluginRelease`, and they agree on almost nothing:
Hangar, GitHub and GeyserMC publish SHA-256, Modrinth publishes SHA-512 and SHA-1 but not SHA-256,
and LuckPerms publishes no checksum at all. So the checksum travels with its algorithm and may be
absent, and freshness falls back to the file name only where it has to.

Several things here were established against the live APIs, not assumed, and are easy to undo by
accident:

- **Stability is a ladder, not a filter.** Hangar and Modrinth are asked for Release, then Beta,
  then Alpha, and the newest build of the first tier that has any is installed. Asking is required
  — unfiltered, Hangar answers ViaVersion with `5.12.0-SNAPSHOT+1053` — and so is falling through:
  Simple Voice Chat publishes thirteen Velocity builds and not one of them is a release. Stability
  wins over recency, so it gets `2.5.28 (Beta)` rather than the newer `2.6.18` alpha. Anything
  below Release carries its channel in the version string so it cannot read as stable in the log.
- **GitHub downloads go through the API asset URL**, with the token and
  `Accept: application/octet-stream`. `browser_download_url` answers 404 for a repository whose
  owner GitHub has flagged — with or without a valid token — while the API serves it normally.
- ***Up to date* is decided by the checksum, not the file name.** Hangar's version string and the
  version inside the file name differ (`5.12.0-SNAPSHOT+1053` ships
  `ViaVersion-5.12.0-SNAPSHOT.jar`). The same digest verifies the download.
- **The updater builds its own `HttpClient`.** The proxy's follows no redirects and is shared with
  Mojang session authentication; GeyserMC answers `builds/latest` with a 302 and GitHub's download
  links point at another host.
- **Geyser and Floodgate cannot come from Hangar.** Their entry there is an external link with
  `fileInfo: null` and a version named "Geyser". `HangarSource` detects that shape and says so.

Pruning older jars of a plugin runs on **both** paths, updated and already-current: two jars
claiming one plugin id make the loader refuse whichever it reads second, and that was a real bug
before it ran on the up-to-date path too.
