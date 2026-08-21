# Getting started

HybridVelocity runs like any Velocity proxy. If you have used Velocity before, everything you know
still applies — this page only covers what is different.

## Install

**Java 25 or newer is required.** Nothing else works — the whole proxy is compiled for it. Starting
on an older runtime prints a message telling you so rather than a stack trace.

Download `HybridVelocity-<version>.jar` from the
[releases](https://github.com/adrielorihuela/HybridVelocity/releases) and run it:

```bash
java -Xms512M -Xmx512M -jar HybridVelocity-1.1.1.jar
```

### Getting more out of the same machine

A proxy forwards packets; it does not simulate a world. That makes it allocation-heavy and
latency-sensitive rather than throughput-bound, so the useful tuning is a small heap that is
collected quickly:

```bash
java -Xms512M -Xmx512M \
     -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=4M \
     -jar HybridVelocity-1.1.1.jar
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
| `config/hybridvelocity.toml` | The configuration. |
| `config/forwarding.secret` | The shared secret for modern player-info forwarding. |
| `plugins/` | Plugins, including the ones listed under `[plugins]`. |
| `lang/` | Message files. This copy wins over the ones inside the jar, so edit here to change wording. |
| `auth/` | The authentication server's settings and the password database. |

If you are upgrading, put the jar in place and start it. Anything from an older layout is moved
into `config/` for you, contents intact — a stock Velocity `velocity.toml` becomes
`config/hybridvelocity.toml`, and an existing `forwarding.secret` is **moved, never regenerated**.
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

## What is on by default

**Offline players are accepted and must authenticate.** A player Mojang cannot verify is not
kicked; they are given an offline identity and held on a small authentication server inside the
proxy until they `/register` or `/login`. Premium players are unaffected.

Turn it off with `enabled = false` under `[offline-auth]` if you want stock Velocity behaviour.

Read [offline-auth.md](offline-auth.md) before running this in production — especially the part
about backing up `auth/player-passwords.db`.

## Keeping plugins up to date

List plugins by their name on [hangar.papermc.io](https://hangar.papermc.io) and the proxy keeps
them current for you:

```toml
[plugins]
hangar = ["ViaVersion", "ViaBackwards"]
```

On every start, before plugins are loaded, each one is downloaded if it is missing and updated if a
newer build exists. They are fetched at once, and start-up waits for the last of them, so the
loader never sees a half-finished directory.

Only the **Release** channel is used. Without asking for it, Hangar answers with whatever is
newest, which for an actively developed plugin is a snapshot — a proxy that moves itself onto
development builds by itself is not what anyone wants.

Whether a plugin is current is decided by the checksum Hangar publishes, not by the file name, and
that same checksum verifies the download, so a truncated file never reaches `plugins/`.

**If something goes wrong**, what happens depends on whether you would be left without the plugin:

- The jar is already installed → a warning, and the proxy starts with the version you have. Hangar
  being down does not take your proxy down.
- The jar is not there at all → the proxy refuses to start, and says which plugin and why. Starting
  without a plugin you listed on purpose is worse than not starting.

**Older jars of the same plugin are deleted**, including ones you put there by hand. Two jars
claiming the same plugin id make the loader refuse one of them, and nothing decides which. Every
deletion is logged.

A plugin not on Hangar is not affected: drop its jar in `plugins/` as usual and leave it off the
list. An empty list makes no network requests at all.

## Backend servers

Backends must be in **offline mode** (`online-mode=false` in their `server.properties`), as with any
proxy, and should only be reachable from the proxy machine. Use `player-info-forwarding-mode =
"modern"` on 1.13+ so backends see real player identities.

## Next

- [offline-auth.md](offline-auth.md) — the register/login gate, its options and its limitations.
- [server-commands.md](server-commands.md) — per-server shortcut commands and permissions.
- [hybrid-offline-profiles.md](hybrid-offline-profiles.md) — how offline identities are built.
