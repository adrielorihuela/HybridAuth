# Portals that lead to another server

HybridAuth can send a player to another server when they go through a Nether or End portal, so
that the portals in your `Lobby` lead to `Survival` instead of to the lobby's own Nether.

## Configuration

The `[portales]` section of `config/HybridAuth.toml` has one entry per server whose portals should
lead somewhere else. A new configuration ships with this one switched on:

```toml
[portales]
lobby = {
    nether = "survival",
    end = "survival"
}
```

Either line can be left out, and that portal then works as it always did. To send only the End to
`Survival`:

```toml
[portales]
lobby = {
    end = "Survival"
}
```

Names are not case-sensitive. Both servers must be in `[servers]`; a name that is not, or a server
pointing at itself, is reported as a warning when the proxy starts and that portal is left alone.
The proxy still starts, since the shipped `[servers]` has no `survival` of its own. Delete the entry
if you do not want it. `/hybridauth reload` picks up changes.

A configuration from an earlier version does not gain the section by itself; add it by hand.

The multi-line form needs TOML 1.1, which the proxy reads. The one-line form,
`lobby = { nether = "survival", end = "survival" }`, works as well. If a configuration migration
ever rewrites the file, night-config writes the entry as a `[portales.lobby]` table, which means the
same thing and is read the same way.

## What the origin server needs

The proxy never sees a portal block. What it sees is the server moving the player into the Nether
or the End, and that is what it acts on. So:

- **Only the dimensions you list must be enabled on the origin server.** With just `end`, only
  `allow-end: true` in `bukkit.yml` is needed; with `nether`, `allow-nether=true` in
  `server.properties`. A disabled dimension makes the portal do nothing, and there is nothing for
  the proxy to notice.
- **Anything that moves a player into those dimensions counts**, not only a portal -- a `/mv tp`
  to the lobby's Nether, for instance, sends them on as well.
- Worlds are recognised by name: `minecraft:the_nether` and `minecraft:the_end`, and any world
  whose name ends in `_nether` or `_the_end`, which covers Multiverse worlds such as
  `lobby_nether`.

When it fires, the proxy logs it: `<player> entered the end on lobby, sending them to survival`.
If a portal does nothing and that line never appears, the origin is not changing the player's
dimension or the entry was reported at start-up.

## What the player sees

The client goes through the usual dimension-change loading screen and then arrives on the target
server. If that server cannot be reached, the player gets the normal "unable to connect" message and
stays in the origin server's Nether or End, exactly as if the redirect were not configured.

The origin server has still moved the player into its Nether or End, so the next time they join it
they will be there. On a lobby, use a plugin that teleports players to spawn when they join.
