---
paths:
  - "limbo/**"
  - "proxy/src/main/java/com/velocitypowered/proxy/auth/**"
  - "proxy/src/main/java/com/velocitypowered/proxy/server/InternalServers.java"
  - "proxy/src/main/resources/auth-server-settings.yml"
  - "proxy/src/test/java/com/velocitypowered/proxy/auth/**"
---

<!-- Moved from CLAUDE.md § Architecture › The authentication server and its subtree on 2026-11-07 -->

### The authentication server and its subtree

`limbo/upstream/` is a pristine `git subtree` of [NanoLimbo](https://github.com/Nan1t/NanoLimbo)
(GPL-3.0, same licence as this fork). The module's build script sits *outside* the subtree and
points `sourceSets` into it, so upstream stays byte-identical and `git subtree pull` does not
conflict. Three upstream files carry local patches, each marked `HybridAuth patch:` and listed
in `docs/development/auth-server.md` — keep that list accurate and the patch surface small.

**Checkstyle is disabled and Spotless excludes `upstream/**` for that module.** This is a
licensing requirement, not a style preference: `velocity-spotless` applies
`licenseHeaderFile(HEADER.txt)`, which replaces everything above the `package` declaration and
would rewrite NanoLimbo's GPL copyright notices to "Velocity Contributors".

`AuthServer` writes `auth/settings.yml` once from the documented template at
`proxy/src/main/resources/auth-server-settings.yml` and then never touches it again, so its comments
survive Configurate, which strips them on save. The bind address and forwarding are deliberately
absent from that file — the operator must not be able to move it off loopback or disagree with the
proxy's forwarding mode — and are passed to `LimboServer` in code. That is what the `override` patch
in the vendored `LimboConfig` is for: its `load()` reads both from disk and throws outright when
`infoForwarding` is missing, so the values have to be installed before loading, not assigned after.

The password database is at a fixed `auth/player-passwords.db`. It needs its own TCP port: two
listeners cannot share one (`SO_REUSEPORT` load-balances rather than multiplexes), and a socketless
in-JVM transport would not be pingable by ViaVersion, which the section below depends on.

<!-- Moved from CLAUDE.md § Architecture › Offline authentication on 2026-11-07 -->

### Offline authentication

The gate (`auth/OfflineAuthGate.java`) is built entirely from events, not by patching the login
handlers. One `ServerPreConnectEvent` listener covers every route to a backend, because they all
end up in `createConnectionRequest`; `PlayerAvailableCommandsEvent` hides commands;
`CommandExecuteEvent` blocks execution; a `PermissionsSetupEvent` function denies permissions.
`PostLoginEvent` must keep firing — an earlier attempt short-circuited it and broke plugins.

Blocking work stays off the event loops: `AuthDatabase` serialises JDBC on one thread and
`OfflineAuthManager` runs bcrypt on its own pool. `PasswordLookup` deliberately distinguishes
`FAILED` from `NOT_REGISTERED`, so a broken database cannot offer `/register` for an account
that already exists.

**The authentication server is registered normally.** It was once kept out of the `ServerMap` to
hide it, which broke ViaVersion: Via pings every server in `getAllServers()` to learn its protocol,
and a server it cannot see falls back to a pre-1.16 assumption and mis-decodes the login. It is
hidden from command output through `server/InternalServers.java` instead — add any new
player-facing server listing to that filter rather than un-registering things. Its name comes from
`[offline-auth] server-name`, so `InternalServers` is told it at start-up, not a constant.

**Bedrock players are never gated** (`auth/BedrockPlayers.java`). Geyser reaches the proxy through
Floodgate's `PreLoginEvent.forceOfflineMode()`, so those players are offline-mode and used to fall
straight into the gate. Detection has two paths on purpose: Floodgate's API is the authoritative one
and the only one that recognises a player who *linked* a Java account, while the UUID shape (top 64
bits zero, which a Java offline UUID can never have) is the fallback. A yes from either is enough,
and no reflective failure may throw — being wrong here must not stop somebody logging in. The API is
reached with `Class.forName(name, false, pluginInstance.getClass().getClassLoader())`: the proxy's
own loader is `PluginClassLoader`'s *parent*, and delegation never runs parent to child, so a plain
`Class.forName` cannot see plugin classes. The gate exposes `isSubjectToAuth` alongside `isGated` —
`/changepassword` repeats the check itself instead of asking `isGated`, so it needs the former.

**`/register` and `/login` are load-bearing.** They are the only way past the gate, so they carry no
permission node (nothing for a permissions plugin to revoke), are re-allowed at `PostOrder.LAST` if
another plugin's `CommandExecuteEvent` listener denies them, and are re-registered after
`ProxyInitializeEvent` if a plugin overwrote the alias. Which of the two a player sees is decided in
the `PlayerAvailableCommandsEvent` handler, which returns an `EventTask` so the packet waits for the
database lookup — the `requires` predicates run earlier, while the answer is still unknown.
