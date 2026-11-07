# Updating the proxy without disconnecting anyone: research

**Status: research only. Nothing on this page is implemented.** It records a design discussion held
with the owner in November 2026, so it can be picked up again without that conversation. Where the
owner made a decision, it says so; everything else is analysis.

The goal: update HybridAuth itself — a new jar, updated plugins, a changed configuration — the way
`plugin/update/` already updates plugins, **without stopping the proxy, without disconnecting a single
player, and without players noticing**. The same mechanism would double as a full hot reload, since
a fresh process reloads everything.

## Why it cannot be done inside the running process

A player is connected by a TCP socket held by the proxy's JVM, and that socket carries live state:

- the AES/CFB8 cipher negotiated at login, whose state changes with every byte sent;
- compression, the protocol version and phase (configuration or play);
- a second connection to the backend, with its own state;
- what the client has already been sent: tab list, boss bars, scoreboards, the signed-chat session;
- whatever plugins hold: listeners, tasks, caches, static maps.

When the process stops, the operating system closes those sockets and every player is kicked. Within
the same process, swapping the code is not viable either:

- **JVM HotSwap** only replaces method bodies. A new Velocity release always adds fields, classes or
  dependencies.
- **Reloading the proxy in a fresh classloader** would leave two copies of the code sharing the same
  Netty objects and players. Velocity is not designed for that.
- **Plugins cannot be unloaded.** Velocity has no unload API, and a plugin leaves behind threads,
  listeners and references that keep it alive. That is why `PluginUpdater` runs only immediately
  before `loadPlugins()`, and why `reloadConfiguration()` (`/hybridauth reload`) does not repeat it.

What already reloads live is the configuration, through `/hybridauth reload`. Plugins and code do not.

## The options that were weighed

### Option 1 — relay between two processes (chosen as the base)

What large networks do: do not touch the players already connected; divert the new ones.

1. **Detect a new version**: check GitHub releases (each release ships `HybridAuth-<version>.jar`, and
   the API gives its SHA-256), download and verify it — the same download and digest logic
   `PluginUpdater` already has.
2. **Start the new process beside the old one**, with the new jar, updated plugins and the new
   configuration. Starting from scratch is what reloads everything.
3. **Share the port.** Velocity already has `enable-reuse-port` under `[advanced]`
   (`default-velocity.toml`; Linux or macOS only). With `SO_REUSEPORT` two processes listen on the
   same port and the kernel spreads new connections between them.
4. **Drain the old process**: once the new one is ready, the old one closes its listener, so every
   new connection goes to the new one; the players already on the old one keep playing there; when
   it is empty, it exits.

Nobody is disconnected. For a while two versions of the proxy coexist. The cost is that the old
process may live for hours, until its last player leaves — which is what option 3 removes.

### Option 2 — Transfer packet (1.20.5+) — rejected by the owner

Since Minecraft 1.20.5 a server can tell the client "reconnect to this host:port". The proxy already
supports it: `Player#transferToHost` (`ConnectedPlayer.java`) and `accepts-transfers` in the
configuration. The old process would store a cookie on the client (`StoreCookie`) with its current
server and a signed token, send the transfer, and the new process would read the cookie and put the
player back on the same backend.

**Rejected because it shows and it reconnects:** the player sees the "connecting" screen for a second
or two; clients older than 1.20.5 (through ViaVersion) and Bedrock players through Geyser cannot be
transferred at all; premium players re-authenticate with Mojang; offline players would need the
signed token honoured by the gate or they would land on the authentication server again; and the
backend sees a leave and a rejoin, which can break in-memory state (an open inventory, combat).

### Option 3 — hand the live sockets to the new process (chosen, on top of option 1)

The only option that is truly invisible. On Linux a process can pass its open sockets to another
through a Unix domain socket (`SCM_RIGHTS`), and Netty's epoll transport can wrap an inherited file
descriptor in a channel — the trick nginx and HAProxy use to reload without dropping connections.

**It combines with option 1**: option 1 is the frame (two processes, shared port, new connections to
the new one); option 3 replaces the draining. Instead of waiting for players to leave, the old process
hands each one over and exits in seconds. Per player:

1. The old process pauses the connection at a packet boundary on **both** sides, client and backend:
   stop reading, flush what is pending.
2. It serialises the state:
   - protocol version and phase, compression threshold;
   - the cipher state in each direction (in CFB8 it is the last 16 ciphertext bytes, so it can be
     tracked);
   - profile, current server, tab list, boss bars;
   - the signed-chat session and its "last seen messages" tracking — without it chat breaks after the
     handover;
   - the offline-auth gate state (whether the player has already done `/login`).
3. It passes both file descriptors (client and backend) to the new process over the Unix socket.
4. The new process wraps them in epoll channels, rebuilds the `ConnectedPlayer` with its handler
   chain, and resumes reading.

**What stays hard even with this option:**

- **Plugins.** Their state does not travel with the player. The new process would have to fire
  synthetic events (login, post-login, server-connected) so LuckPerms and the rest load the player,
  without sending them a single login packet. Every plugin that caches things in its own way is a
  risk; this must be tested against the actual plugins in use.
- **Geyser as a proxy plugin.** Bedrock players arrive over UDP/RakNet inside Geyser, and that cannot
  be passed between processes. For them not to be disconnected either, Geyser has to run standalone,
  outside the proxy.
- **Linux only.** `SCM_RIGHTS`, epoll and this use of `SO_REUSEPORT` have no Windows equivalent.
- **The diff against upstream.** This touches the core of the connection code, exactly where
  Velocity changes most often, so every upstream merge would conflict more and the automatic upstream
  update would stop for review more often. It goes against "keep diffs against upstream narrow".
- **Memory.** During the handover two JVMs run at once; the host needs RAM for both for a few seconds.

Assessment: a large project, but feasible. Recommended order: option 1 alone (draining) first, which
works by itself; the socket handover as a later improvement.

## The owner's decisions

- **Base: option 1**, with **option 3** on top of it. **No option 2**: it shows and it reconnects.
- **Passwords move to an external database.** Today `AuthDatabase` uses SQLite at a fixed
  `auth/player-passwords.db`, serialised on one thread; two processes writing it at once is unsafe.
- **The authentication server runs as a separate server.** It is NanoLimbo, a standalone server
  vendored and run in-process here (`AuthServer`). The proxy already registers it in `[servers]` like
  any other, and the gate only needs its name (`[offline-auth] server-name`). Run separately, the
  operator — not the code, as today — must make sure it listens only on loopback or a private network
  (otherwise anyone can join it without the proxy) and uses the same forwarding mode and secret. Today
  it needs its own TCP port (30065 by default) because two listeners cannot share one.
- **`forwarding.secret` goes in a shared folder** above both processes. It is enough that
  `forwarding-secret-file` points there in both configurations. It must never be regenerated
  (CLAUDE.md explains why).

## The launcher jar (owner's idea, endorsed)

The owner asked whether the main jar could be only an installer, with the real process in another
jar. Yes — and it is the piece that makes the design work, framed as a **small, stable supervisor that
rarely changes** rather than an installer:

- **The launcher is the process the operator starts.** It does not contain the proxy. Something
  similar exists already: `proxy/src/bootstrap/`, a Java 8 class that checks the Java version and
  then enters the real proxy by reflection. The launcher is that idea extended.
- **It owns the port.** It opens the listening socket once and passes it to each proxy child by file
  descriptor. Then not even `SO_REUSEPORT` is needed: the port never closes, whichever proxy is
  running.
- **It drives updates:**
  1. checks GitHub releases;
  2. downloads `HybridAuth-<version>.jar` and verifies its SHA-256 (as `PluginUpdater` does);
  3. starts the new proxy as **another process**, with the new jar, updated plugins and the new
    configuration;
  4. coordinates the relay: tells the old one to stop accepting connections and, with option 3, joins
    the two processes over a Unix socket so the old one hands its players over;
  5. waits for the old one to finish and stops it.
- **It never carries player traffic.** It only passes sockets and signals. If bytes went through it,
  the launcher would become another proxy and the problem of never being able to restart it returns.
- **Separate processes, not the new jar loaded into the same JVM.** With classloaders in one JVM the
  root problems come back: plugins that do not unload, memory that is not freed, two versions sharing
  state. With two processes, the old one dies whole and takes everything with it.
- **Updating the launcher itself** is rare, since it does almost nothing: replace it on disk for the
  next full restart, or, on Linux, re-`exec` itself keeping the listening socket and without touching
  the proxy children, since it holds no players.

Summary: **launcher + option 1 updates and reloads everything without disconnecting anyone**; the
socket handover (option 3) goes on top so there is no wait for the old process to empty.

## Where to start when this is picked up

Nothing has been planned in detail yet. A sensible first phase, the least risky and useful on its
own:

1. Move the password store to an external database behind `AuthDatabase`, keeping SQLite as the
   default.
2. Document and support running the authentication server separately.
3. The launcher: version check, download and digest verification, starting the proxy as a child,
   owning the port and passing it down.
4. Draining (option 1): a control channel between launcher and proxy for "ready" / "close your
   listener".

Then, as a separate phase, the socket handover (option 3), starting with the cipher and protocol
state and a test against the plugins actually in use (LuckPerms, ViaVersion, Floodgate; Geyser
standalone).

Code this builds on: `plugin/update/PluginUpdater` (download, checksum), `proxy/src/bootstrap/` (the
reflective entry point), `enable-reuse-port` and `accepts-transfers` in `default-velocity.toml`,
`ConnectedPlayer#transferToHost`, `auth/AuthDatabase.java`, `auth/AuthServer.java`,
`VelocityServer#reloadConfiguration`.
