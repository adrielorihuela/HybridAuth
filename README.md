# HybridAuth

**Player authentication and automatic plugin updates for Velocity proxies running in offline
mode.** A fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft proxy by PaperMC.

Velocity already ships `online-mode` as a supported configuration option. Turning it off is
ordinary upstream behaviour, and on stock Velocity it means any client can claim any username with
no password at all. HybridAuth adds an authentication layer on top of that mode: players Mojang
cannot verify must register a password and log in before they reach any of your servers.

It also keeps your plugins up to date on its own, from Hangar, Modrinth, GitHub, GeyserMC and
LuckPerms.

Licensed under the GPLv3, like Velocity.

## What this does and does not do

**It does not change how premium players are authenticated.** They are verified by Mojang's session
server exactly as they are on upstream Velocity. That path is untouched, and no part of it is
skipped, weakened or worked around. A premium player sees no prompt and no password.

**It is not a launcher, it does not distribute Minecraft, and it has nothing to do with owning the
game.** It is server-side software. It never touches the client.

**It makes an existing Velocity configuration stricter, not looser.** Compared with the
`online-mode = false` that Velocity supports today:

| | Velocity in offline mode | With HybridAuth |
| --- | --- | --- |
| Unverified player joins | Reaches your servers immediately | Held on an internal server until they register or log in |
| Password | None | bcrypt, with a per-record salt |
| Claiming a premium player's name | Possible — any client can send any username | Impossible: identities are kept in separate namespaces |
| Authentication unavailable | Not applicable | Player is refused, never let through |

**Premium and unverified players share one address**, and the proxy can tell them apart at all
times.

## How it works

**Players Mojang can verify are handled by Mojang.** The proxy performs the standard encryption
handshake and asks the session server, exactly as upstream does. A verified player connects
straight through.

**Players Mojang cannot verify are authenticated by the proxy instead of being let in.** They are
given an offline identity and held on a small authentication server running inside the proxy —
there is nothing extra to install — until they `/register` or `/login`. Only then do they reach a
real server.

**Passwords are stored as bcrypt hashes** with a per-record salt, in an embedded SQLite database.
Hashing runs off the network threads. Everything fails closed: if the database or the
authentication server is unavailable, the player is refused rather than let through.

**Identities cannot collide.** An unverified `Steve123` becomes `Steve123.`, with a UUID derived
from that dotted name. A username containing a dot is not a valid Minecraft account name, so these
identities occupy a namespace no paid account can ever reach. Nobody can take a premium player's
name.

**Bedrock players are never asked for a password.** Anyone arriving through
[Geyser](https://geysermc.org) has already been authenticated against Xbox Live by Floodgate, and
asking again would be asking twice for an identity somebody else already verified. This is not an
option to find; it is simply how it behaves.

Full details, including what to back up: [docs/guide/offline-auth.md](docs/guide/offline-auth.md).

## Plugins that update themselves

Independent of the above, and useful whichever mode you run in. List what you want and the proxy
keeps it current:

```toml
[plugins]
hangar = [
    "ViaVersion"
]
modrinth = [
    "ViaBackwards"
]
github = [
    "ViaVersion/ViaRewind"
]
geyser = [
    "geyser",
    "floodgate"
]
luckperms = false
```

- **Five sources.** [Hangar](https://hangar.papermc.io), [Modrinth](https://modrinth.com), GitHub
  releases, GeyserMC and LuckPerms, mixed freely in one list.
- **Before plugins load, not after.** Everything is fetched in parallel and start-up waits for the
  last one, so the plugin loader never reads a half-finished directory.
- **Verified by checksum, never by file name.** A name proves only that something of that name is
  there. The digest the source publishes decides whether an update is needed, and the same digest
  verifies the download, so a corrupted file never reaches `plugins/`.
- **Only what changed is downloaded.** A start with nothing to do transfers a few kilobytes.
- **Stable builds first.** Release, then beta, then alpha — the newest build of the highest tier
  available, with the channel shown in the log when it is not a release.
- **Old jars are removed.** Two jars claiming one plugin id make the loader refuse whichever it
  reads second; every deletion is logged.
- **It fails safely.** If a source is unreachable but the jar is installed, the proxy warns and
  starts with what you have. If the jar is missing entirely, it refuses to start rather than run
  without a plugin you listed.

Full details: [docs/guide/getting-started.md](docs/guide/getting-started.md).

## Also

**A command per server.** List a server under `comandos` and players get `/lobby` instead of
`/server lobby`, permission-controlled per server.

**Portals that lead to another server.** Step into the End portal in your lobby and arrive on
survival. One entry per server, one line per dimension, and only the dimensions you list need
enabling there:

```toml
[portales]
lobby = {
    nether = "survival",
    end = "survival"
}
```

Details: [docs/guide/portal-redirects.md](docs/guide/portal-redirects.md).

**Everything else is Velocity.** Same plugin API, same performance, same configuration. Works with
ViaVersion, ViaBackwards, ViaRewind, Geyser, Floodgate, LuckPerms and every other Velocity plugin.

## Quick start

Download `HybridAuth-<version>.jar` from the
[releases](https://github.com/adrielorihuela/HybridAuth/releases) — the newest one at the top — and
run it:

```bash
java -Xms512M -Xmx512M -jar HybridAuth-<version>.jar
```

A new release goes out by itself whenever something changes, including whenever Velocity does: the
fork follows upstream automatically, usually within the hour.

Java 25 is required.

Upgrading from stock Velocity? Swap the jar and start it. Your `velocity.toml` is moved to
`config/HybridAuth.toml` with its contents intact, and your `forwarding.secret` is moved rather
than regenerated. Permissions keep working: the `velocity.command.*` nodes are still honoured
alongside the new `hybridauth.command.*` ones.

Then read [docs/guide/getting-started.md](docs/guide/getting-started.md).

## Documentation

- **[docs/guide/](docs/guide/README.md)** — running the proxy: installation, the authentication
  gate and its options, the plugin updater, the per-server commands, the portal redirects.
- **[docs/development/](docs/development/README.md)** — how it was built: the specification, the
  protocol research, the attempt that failed and the architecture that replaced it — and how it is
  kept current: releases, and the automatic updates from Velocity and NanoLimbo.

Anything not covered there behaves as upstream, so the
[Velocity documentation](https://docs.papermc.io/velocity) still applies.

## Building

```bash
./gradlew build
```

Uses the Gradle wrapper (`./gradlew.bat` on Windows) and runs Checkstyle, Spotless and the tests.
The runnable jar is `proxy/build/libs/HybridAuth-<version>.jar`. Java 25 is required.

## Upstream

A derivative of Velocity by the PaperMC team, and it bundles
[NanoLimbo](https://github.com/Nan1t/NanoLimbo) (also GPL-3.0) as the authentication server. Bugs
that also reproduce on upstream Velocity belong at
[PaperMC/Velocity](https://github.com/PaperMC/Velocity), not here.
