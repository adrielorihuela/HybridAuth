# Portals that lead to another server

HybridAuth can send a player to another server when they go through a Nether or End portal, so
that the portals in your `Lobby` lead to `Survival` instead of to the lobby's own Nether.

## Configuration

Add a `[portales]` section to `config/HybridAuth.toml`, with one line per server whose portals
should lead somewhere else:

```toml
[servers]
Lobby = "127.0.0.1:30066"
Survival = "127.0.0.1:30067"
try = ["Lobby"]

[portales]
Lobby = { nether = "Survival", end = "Survival" }
```

Either `nether` or `end` can be left out, and that portal then works as it always did. Both the
server on the left and the one it sends players to must be in `[servers]`, and a server cannot
send players to itself; the proxy refuses to start otherwise, and says which line is wrong. Names
are not case-sensitive. `/hybridauth reload` picks up changes.

The section is absent by default, and without it nothing changes.

## What the origin server needs

The proxy never sees a portal block. What it sees is the server moving the player into the Nether
or the End, and that is what it acts on. So:

- **The Nether and the End must be enabled on the origin server** — `allow-nether=true` in
  `server.properties` and `allow-end: true` in `bukkit.yml`. With them disabled the portal does
  nothing and there is nothing for the proxy to notice.
- **Anything that moves a player into those dimensions counts**, not only a portal — a `/mv tp`
  to the lobby's Nether, for instance, sends them on as well.
- Worlds are recognised by name: `minecraft:the_nether` and `minecraft:the_end`, and any world
  whose name ends in `_nether` or `_the_end`, which covers Multiverse worlds such as
  `lobby_nether`.

## What the player sees

The client goes through the usual dimension-change loading screen and then arrives on the target
server. If that server cannot be reached, the player gets the normal "unable to connect" message and
stays in the origin server's Nether or End, exactly as if the redirect were not configured.

The origin server has still moved the player into its Nether or End, so the next time they join it
they will be there. On a lobby, use a plugin that teleports players to spawn when they join.
