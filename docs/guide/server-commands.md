# Per-server shortcut commands

HybridAuth can create a command for each of your servers, so that a player can type
`/Lobby` to be sent to the `Lobby` server instead of `/server Lobby`.

## Configuration

The servers that get a command are listed in the `comandos` option, inside the `[servers]`
section of `config/HybridAuth.toml`:

```toml
[servers]
Lobby = "127.0.0.1:30066"
Survival = "127.0.0.1:30067"
Parkour = "127.0.0.1:30068"

# In what order we should try servers when a player logs in or is kicked from a server.
try = [
    "Lobby"
]

# Servers that also get their own command.
comandos = [
    "Lobby",
    "Survival"
]
```

With the configuration above, `/Lobby` and `/Survival` exist, while `/Parkour` does not —
players can still reach Parkour with `/server Parkour`.

Rules:

* Every name in `comandos` must also be a key in `[servers]`. If it is not, the proxy logs
  `Server command '<name>' is not registered in your configuration!` and refuses to start,
  the same way an unknown entry in `try` does.
* The option is optional. Leaving it out, or setting it to `[]`, disables the feature.
* Commands are case-insensitive, like every Velocity command: `/Lobby`, `/lobby` and
  `/LOBBY` all work regardless of how the server name is capitalised in the config.
* A name that collides with an existing command (`server`, `send`, `glist`, `velocity`,
  `stop`, or one registered by a plugin) is skipped with a warning in the console, so a
  server called `send` will not shadow `/send`.

## Permissions

Each shortcut checks its own node, `hybridauth.command.server.<server name in lowercase>` — for
example `hybridauth.command.server.lobby` for `/Lobby`. `/server` checks
`hybridauth.command.server`. A shortcut does not depend on `/server`, and `/server <name>` does not
check the shortcut's node, so denying `/Lobby` does not stop `/server Lobby`.

These commands are allowed unless a permission says otherwise, like Velocity's own `/server`:

* With no permissions plugin installed, every player can use every configured command.
* Setting the node to **false** hides and blocks the command; setting it to **true** allows it.
* The node's value decides on its own. If it is set, true or false, nothing else is consulted.
* If it is not set, upstream Velocity's `velocity.command.server.<server>` (and
  `velocity.command.server` for `/server`) is read instead, so a permission set up for stock
  Velocity keeps working. If neither is set, the command is allowed.

So `hybridauth.command.*` always overrides `velocity.command.*`, and having both set is never a
conflict: a player whose default group denies `velocity.command.server` but who is granted
`hybridauth.command.server` can use `/server`.

With LuckPerms, set the value as `true` or `false` at the end of the command. Do not put a `-` in
front of the node: LuckPerms does not read that as a negation, it creates a different node with a
`-` in its name that nothing checks.

```
# Nobody in the default group gets /lobby...
lp group default permission set hybridauth.command.server.lobby false
# ...except VIPs.
lp group vip permission set hybridauth.command.server.lobby true

# Take /server away from the default group, and give it back to one player.
lp group default permission set hybridauth.command.server false
lp user Steve123 permission set hybridauth.command.server true

# Undo: the command is allowed again unless velocity.command.server.lobby says otherwise.
lp group default permission unset hybridauth.command.server.lobby
```

`hybridauth.command.server.*` matches every shortcut at once, provided LuckPerms' `apply-wildcards`
is left on (its default). Set `hybridauth.command.server` separately for `/server` itself.

Because each server has its own node, per-server access can be given to different ranks without
touching `/server`. Denied commands are also removed from the player's command list, so they do not
show up in tab completion. That list is sent when the backend server sends its own, which is when
the player joins or changes server, so a permission changed while a player is online blocks the
command straight away but may leave it in their tab completion until then.

## Reloading

`/hybridauth reload` re-reads `comandos`. Commands that were removed from the list are
unregistered and newly added ones are registered, without restarting the proxy.

## Console

These are player commands. Running one from the console does nothing, just like `/server`.
