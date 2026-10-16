# Getting started

HybridAuth runs like any Velocity proxy. If you have used Velocity before, everything you know
still applies — this page only covers what is different.

## Install

**Java 25 or newer is required.** Nothing else works — the whole proxy is compiled for it. Starting
on an older runtime prints a message telling you so rather than a stack trace.

Download `HybridAuth-<version>.jar` from the
[releases](https://github.com/adrielorihuela/HybridAuth/releases) and run it:

```bash
java -Xms512M -Xmx512M -jar HybridAuth-1.1.1.jar
```

### Getting more out of the same machine

A proxy forwards packets; it does not simulate a world. That makes it allocation-heavy and
latency-sensitive rather than throughput-bound, so the useful tuning is a small heap that is
collected quickly:

```bash
java -Xms512M -Xmx512M \
     -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=4M \
     -jar HybridAuth-1.1.1.jar
```

Set `-Xms` equal to `-Xmx` so the heap is never resized at runtime. 512 MB is plenty for a few
hundred players; giving a proxy several gigabytes makes pauses worse, not better, because the
collector has more to walk.

On Linux the proxy uses epoll or io_uring automatically, and native compression and encryption when
available — those are already on and need no flags. The log line at startup tells you which it
picked.

On first start it creates:

| | |
| --- | --- |
| `config/HybridAuth.toml` | The configuration. |
| `config/forwarding.secret` | The shared secret for modern player-info forwarding. |
| `plugins/` | Plugins, including the ones listed under `[plugins]`. |
| `lang/` | Message files. This copy wins over the ones inside the jar, so edit here to change wording. |
| `auth/` | The authentication server's settings and the password database. |

If you are upgrading, put the jar in place and start it. Anything from an older layout is moved
into `config/` for you, contents intact. A stock Velocity `velocity.toml`, or this fork's earlier
`hybridvelocity.toml` or `proxy.toml`, all become `config/HybridAuth.toml`, and an existing
`forwarding.secret` is **moved, never regenerated**.
That last part matters: a new secret would be accepted silently by the proxy and rejected by every
backend using modern forwarding.

## Configure your servers

Same as Velocity — list them under `[servers]` and pick the connection order with `try`:

```toml
[servers]
lobby = "127.0.0.1:30066"
survival = "127.0.0.1:30067"

try = [
    "lobby"
]

comandos = [
    "lobby"
]
```

`comandos` is this fork's addition: every server listed there also gets a command named after it, so
players can type `/lobby` instead of `/server lobby`. See [server-commands.md](server-commands.md).

## Portals to another server

A new configuration also sends the lobby's Nether and End portals to a server called `survival`:

```toml
[portales]
lobby = {
    nether = "survival",
    end = "survival"
}
```

Change the names to your own servers, leave out the line for a portal that should keep working as
usual, or delete the entry to turn it off. A name missing from `[servers]` is reported at start-up
and ignored, so the proxy starts either way. See [portal-redirects.md](portal-redirects.md).

## What is on by default

**Offline players are accepted and must authenticate.** A player Mojang cannot verify is not
kicked; they are given an offline identity and held on a small authentication server inside the
proxy until they `/register` or `/login`. Premium players are unaffected.

Turn it off with `enabled = false` under `[offline-auth]` if you want stock Velocity behaviour.

Read [offline-auth.md](offline-auth.md) before running this in production — especially the part
about backing up `auth/player-passwords.db`.

## Keeping plugins up to date

List the plugins you want and the proxy keeps them current for you. Four sources, and you can mix
them freely:

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

| List | What goes in it |
| --- | --- |
| `hangar` | Project names on [hangar.papermc.io](https://hangar.papermc.io), as in the project URL. Case does not matter. |
| `modrinth` | Project names on [modrinth.com](https://modrinth.com) — the name in the URL, which is not always the title. |
| `github` | Repositories as `owner/repo`. The owner cannot be left out; nothing can guess it. Add `:pattern` to pick between several jars in one release. |
| `geyser` | `geyser` and `floodgate`, from GeyserMC directly. |
| `luckperms` | A switch, not a list: there is one LuckPerms, and on a proxy the platform is always Velocity. |

On every start, before plugins are loaded, each one is downloaded if it is missing and updated if a
newer build exists. They are fetched all at once and start-up waits for the last of them, so the
loader never sees a half-finished directory.

**Stable builds first, and only as far down as it has to go.** Hangar and Modrinth are asked for
a release, then a beta, then an alpha, and the newest build of the highest tier available is the
one installed. Asking matters — unfiltered, Hangar answers ViaVersion with `5.12.0-SNAPSHOT+1053`
— and so does falling through: Simple Voice Chat has thirteen Velocity builds and not one is a
release, so refusing outright would mean you could not install a plugin that exists and works.

Stability wins over recency, which is worth knowing before it surprises you: Simple Voice Chat
installs `2.5.28 (beta)` rather than the newer `2.6.18`, because 2.6.18 is an alpha. Anything below
a release carries its channel in the log, so a beta never reads as a stable build.

Whether a plugin is current is decided by the checksum its source publishes, not by the file name,
and that same checksum verifies the download, so a truncated file never reaches `plugins/`.
LuckPerms is the one source that publishes no checksum at all — there the file name is what there
is, and the log says so rather than leaving it looking like something was verified.

**Geyser and Floodgate have their own list** rather than going under `hangar`. They are listed on
Hangar, but only as a link somewhere else: no file name, no checksum, and a version named literally
"Geyser". There is nothing there to compare against or verify, so they are fetched from GeyserMC,
which publishes all three.

### If something goes wrong

What happens depends on whether you would be left without the plugin:

- The jar is already installed → a warning, and the proxy starts with the version you have. A
  source being down does not take your proxy down.
- The jar is not there at all → the proxy refuses to start, and says which plugin and why. Starting
  without a plugin you listed on purpose is worse than not starting.

**Older jars of the same plugin are deleted**, including ones you put there by hand. Two jars
claiming the same plugin id make the loader refuse whichever it reads second, and nothing decides
which that is. Every deletion is logged.

A plugin from anywhere else is unaffected: drop its jar in `plugins/` as usual and leave it off the
lists. Empty lists make no network requests at all.

### A GitHub token, if you need one

GitHub allows 60 unauthenticated requests an hour from one address. That is plenty for a few
plugins and not enough for a machine that restarts constantly. A token raises it to 5000.

Put it in `config/github.token`, one line and nothing else. The file is created empty for you.

A token is not only about the limit. **A repository belonging to an account GitHub has flagged, or
any private repository, answers 404 on its normal download link — with or without a token.** The
proxy fetches GitHub releases through the API instead, which serves them normally when a token is
present, so listing your own repository works even in that state.

```toml
[plugins]
github-token-file = "github.token"
```

It lives in its own file for the same reason the forwarding secret does: configurations get pasted
into issues and screenshots, and a token in one is a credential handed to whoever reads it. A
fine-grained token with **no permissions at all** is enough — this only reads public releases. A
`GITHUB_TOKEN` environment variable is used instead when it is set.

## Backend servers

Backends must be in **offline mode** (`online-mode=false` in their `server.properties`), as with any
proxy, and should only be reachable from the proxy machine. Use `player-info-forwarding-mode =
"modern"` on 1.13+ so backends see real player identities.

## Next

- [offline-auth.md](offline-auth.md) — the register/login gate, its options and its limitations.
- [server-commands.md](server-commands.md) — per-server shortcut commands and permissions.
- [portal-redirects.md](portal-redirects.md) — Nether and End portals that lead to another server.
- [hybrid-offline-profiles.md](hybrid-offline-profiles.md) — how offline identities are built.
