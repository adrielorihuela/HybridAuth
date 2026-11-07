---
paths:
  - "proxy/src/main/java/com/velocitypowered/proxy/command/**"
  - "proxy/src/test/java/com/velocitypowered/proxy/command/**"
  - "proxy/src/main/resources/com/velocitypowered/proxy/l10n/**"
---

<!-- Moved from CLAUDE.md § Architecture › Commands and permissions on 2026-11-07 -->

### Commands and permissions

Commands are Brigadier nodes registered through `VelocityCommandManager`, which lowercases
aliases (so commands are case-insensitive regardless of the literal's casing). The proxy's own
command is `/hybridauth`, with `velocity` as its only alias.

Permissions are tri-state and are checked **only** through the two helpers in
`command/builtin/BuiltinCommandUtil.java`, never by calling `getPermissionValue` directly. Nodes are
`hybridauth.command.*`, and each helper also consults upstream's `velocity.command.*` node so that
a permission set up for stock Velocity keeps working. Both helpers share one rule, in `resolve`:
**the `hybridauth` node decides whenever it is set, true or false; otherwise the `velocity` node
decides; otherwise the command's default applies.** Only the default differs:

- `isAllowed` — privileged commands, denied by default.
- `isNotDenied` — `/server` and the per-server shortcuts, allowed by default.

`isNotDenied` used to deny when *either* node said no. With LuckPerms that made the current node
useless on its own: anything resolving `velocity.command.server` to false — a default group, a
wildcard, a leftover from stock Velocity — silently overrode a grant on
`hybridauth.command.server`, and operators could only make it work by setting the `velocity` node.
That was the 1.3.1 permission fix; `ServerPermissionTest` covers every combination for `/server`
and a shortcut, and must keep doing so.

**`velocity.command.*` also happens to be the prefix of the translation keys** in
`messages*.properties`. They are not permissions. A blanket rename across that prefix would rewrite
34 locale files and break anyone overriding a translation.

Equally off-limits: the `com.velocitypowered` packages, the `velocity-api`/`velocity-proxy` module
names, the `velocity:player_info` forwarding channel that Paper hardcodes, and the `"velocity"`
virtual-plugin id that plugins declare as a dependency.

<!-- Moved from CLAUDE.md § Architecture › Translations on 2026-11-07 -->

### Translations

User-facing strings are MiniMessage translation keys in
`proxy/src/main/resources/com/velocitypowered/proxy/l10n/messages*.properties`, resolved
through `Component.translatable`. Add new keys to `messages.properties`; the files are copied
to a `lang/` directory next to the proxy on first run, and that copy wins at runtime — delete
it when testing changed strings.
