# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

HybridVelocity is a fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft
server proxy. Most of the tree is upstream code.

**Read the docs before changing anything in login, configuration, commands or the `:limbo`
module.** They are not background reading — most of what is written there exists to stop a specific
mistake being made twice.

| Where | What it answers |
| --- | --- |
| `docs/guide/` | How an operator uses the fork. Start at `docs/guide/getting-started.md`. |
| `docs/development/README.md` | Why the fork is built this way: the specification, the attempt that failed, the protocol research, the architecture chosen. |
| `docs/development/updating-upstreams.md` | **How to pull changes from Velocity or NanoLimbo.** Read this before any upstream work — merging and rebasing are not interchangeable here. |
| `docs/development/auth-server.md` | The vendored subtree, its two local patches, and why each exists. |

Fork-specific behaviour:

- **Hybrid offline profiles** (`connection/client/InitialLoginSessionHandler.java`) — when the
  Mojang session server returns 204, the player is accepted with a `.`-suffixed username and
  an offline UUID derived from it, instead of being disconnected. See
  `docs/guide/hybrid-offline-profiles.md`.
- **Offline authentication** (`proxy/.../auth/`, `offline-auth` in the config) — unauthenticated
  offline players are held on an authentication server until they `/register` or `/login`. On by
  default. See `docs/guide/offline-auth.md`.
- **The authentication server** (`:limbo` module, `auth/AuthServer.java`) — a vendored copy of
  NanoLimbo run in-process. "Limbo" is only the upstream project's name; everything
  operator-facing calls it the authentication server. See `docs/development/auth-server.md`.
- **Per-server shortcut commands** (`command/builtin/ServerShortcutCommand.java`, registered
  from `VelocityServer#registerServerCommands`) — the `comandos` list in `[servers]`.
  See `docs/guide/server-commands.md`.

The configuration file is `hybridvelocity.toml`; `ConfigurationLocation` renames an
upstream-named `velocity.toml` on first start. Permission nodes keep the `velocity.` prefix —
they are identifiers operators have already granted, not branding.

Keep diffs against upstream narrow so upstream merges stay manageable.

`main` is a linear continuation of upstream Velocity's `dev/4.0.0`, so upstream changes are
**merged**, never rebased in: the NanoLimbo subtree was imported with a merge commit that
`git subtree pull` looks for, and rebasing would drop it and rewrite published history.
`docs/development/updating-upstreams.md` has the procedure and the conflict-prone files.

## Commands

The Gradle wrapper is the entry point; on Windows use `./gradlew.bat`. The build targets a
Java 25 toolchain, and CI runs on JDK 25 too.

```bash
./gradlew build
```

Full cycle for all modules: compile, Checkstyle, Spotless check, tests, distributions.

```bash
./gradlew :velocity-proxy:test --tests "com.velocitypowered.proxy.config.ServersConfigTest"
```

Run one test class (drop `--tests` for the whole proxy module). Modules are named
`:velocity-api`, `:velocity-proxy`, `:velocity-native`.

```bash
./gradlew :velocity-proxy:spotlessApply
```

Apply formatting — run before committing, the build fails on style violations.

```bash
./gradlew :velocity-proxy:runShadow
```

Run the proxy locally; it generates `hybridvelocity.toml`, `forwarding.secret` and `lang/` in
`proxy/run/`. The build produces two jars in `proxy/build/libs/`:
`HybridVelocity-<forkVersion>.jar` is the runnable distributable and the one to attach to a
release; `HybridVelocity-<forkVersion>-thin.jar` is not runnable and only feeds `shadowJar`.

### Versioning

Two versions coexist, and they are not interchangeable:

- `version` in `gradle.properties` (`4.1.0-SNAPSHOT`) is the **upstream Velocity API version**.
  It sets the Maven coordinates of `velocity-api`, so plugins compile against it — do not bump
  it to express a fork release.
- `forkName` / `forkVersion` in `gradle.properties` name the **fork's distributable**. The
  proxy's `shadowJar` uses them for the archive name and manifest, and `VelocityServer#getVersion`
  reads that manifest, which is why the proxy logs `Booting up HybridVelocity <forkVersion>`.
  Bump `forkVersion` for a fork release and tag it `v<forkVersion>`.

### Java 25 is a hard requirement

Nothing sets `options.release`, so the toolchain's 25 is also the bytecode target and the jar will
not load on anything older. The one exception is `proxy/src/bootstrap/`, a single-class source set
compiled with `options.release = 8` that is the jar's `Main-Class`: it loads anywhere, checks
`java.specification.version` and explains the problem instead of letting the classloader throw
`UnsupportedClassVersionError`. It reaches the real entry point by reflection so the Java 25 classes
are not linked before the check passes, and it must stay free of anything newer than Java 8.

## Architecture

### Connection lifecycle

A player connection is a Netty channel wrapped in `MinecraftConnection`, which delegates every
packet to a single **active session handler** (`MinecraftSessionHandler`). Progressing through
the protocol means swapping that handler via
`MinecraftConnection#setActiveSessionHandler(StateRegistry, handler)` — this both installs the
handler and switches the packet registry, and must run on the connection's event loop.

The client-side chain is `HandshakeSessionHandler` → `StatusSessionHandler` (ping) or
`InitialLoginSessionHandler` (encryption + Mojang auth) → `AuthSessionHandler` (builds the
`ConnectedPlayer`) → `ClientConfigSessionHandler` / `ClientPlaySessionHandler`. The proxy holds
a second connection per player to the backend server (`VelocityServerConnection`) with its own
chain in `connection/backend/`: `LoginSessionHandler` → `TransitionSessionHandler` →
`BackendPlaySessionHandler`.

`StateRegistry` (`protocol/StateRegistry.java`) is the single source of truth mapping packet
IDs to classes per protocol state and per Minecraft version. Adding or changing a packet means
editing the relevant state's `serverbound`/`clientbound` registrations there, keyed by
`ProtocolVersion` ranges. The Netty pipeline in `protocol/netty/` handles framing, compression
and encryption around it.

Because everything runs on Netty event loops, blocking work must be dispatched off-loop
(see `VelocityScheduler`), and code touching a connection must respect its event loop.

### Wiring

`VelocityServer` is the composition root: it constructs the command manager, event manager,
plugin manager, scheduler, `ServerMap` and `ConnectionManager`, then `start()` registers
built-in commands, loads the configuration, registers servers, loads plugins, fires
`ProxyInitializeEvent` and binds. Note the ordering constraint: built-in commands are
registered *before* the config is loaded, so anything that depends on configuration (like the
server shortcut commands) must be registered after the server-registration loop.
`reloadConfiguration()` is the mirror image for `/hybridvelocity reload` and must be updated
whenever startup gains configuration-derived state.

`VelocityConfiguration` deserializes `hybridvelocity.toml` with night-config, using
`proxy/src/main/resources/default-velocity.toml` as the template written on first run.
Sub-tables (`[servers]`, `[forced-hosts]`, `[advanced]`, …) map to private static inner
classes. `validate()` is the place for cross-checks between sections, and
`config/migration/` holds `ConfigurationMigration` implementations that rewrite older configs
in place — add one when an existing key changes shape.

`api/` is the public plugin API and must stay source-compatible; `proxy/` implements it.
`native/` provides libdeflate/OpenSSL acceleration with pure-Java fallbacks, so nothing may
assume the native path is available. `limbo/` is vendored third-party source — see below.

### The authentication server and its subtree

`limbo/upstream/` is a pristine `git subtree` of [NanoLimbo](https://github.com/Nan1t/NanoLimbo)
(GPL-3.0, same licence as this fork). The module's build script sits *outside* the subtree and
points `sourceSets` into it, so upstream stays byte-identical and `git subtree pull` does not
conflict. Two upstream files carry local patches, both marked `HybridVelocity patch:` and listed
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

### Commands and permissions

Commands are Brigadier nodes registered through `VelocityCommandManager`, which lowercases
aliases (so commands are case-insensitive regardless of the literal's casing). The proxy's own
command is `/hybridvelocity`, with `hv` and `velocity` as aliases.

Permissions are tri-state and are checked **only** through the two helpers in
`command/builtin/BuiltinCommandUtil.java`, never by calling `getPermissionValue` directly. Nodes are
`hybridvelocity.command.*`, and each helper also consults the pre-rename `velocity.command.*` node so
that renaming did not revoke anything operators had already granted:

- `isAllowed` — privileged commands. Granted only if a node says so, and the legacy node is read
  only when the current one is unset, so an explicit deny always wins.
- `isNotDenied` — `/server` and the per-server shortcuts, which everyone has unless denied. **Either**
  node saying no denies. A plain OR here would stop honouring a pre-rename
  `-velocity.command.server.<name>`, which is why the two helpers exist rather than one.

**`velocity.command.*` also happens to be the prefix of the translation keys** in
`messages*.properties`. They are not permissions. A blanket rename across that prefix would rewrite
34 locale files and break anyone overriding a translation.

Equally off-limits: the `com.velocitypowered` packages, the `velocity-api`/`velocity-proxy` module
names, the `velocity:player_info` forwarding channel that Paper hardcodes, and the `"velocity"`
virtual-plugin id that plugins declare as a dependency.

### Translations

User-facing strings are MiniMessage translation keys in
`proxy/src/main/resources/com/velocitypowered/proxy/l10n/messages*.properties`, resolved
through `Component.translatable`. Add new keys to `messages.properties`; the files are copied
to a `lang/` directory next to the proxy on first run, and that copy wins at runtime — delete
it when testing changed strings.

## Conventions

Google Java Style enforced by Checkstyle and Spotless: two-space indentation, braces on the
same line, sorted imports, no unused imports. Preserve nullness annotations (Checker
Framework) and the asynchronous event-loop behaviour of anything you touch.

Tests use JUnit Jupiter, with Mockito available in the proxy module. Place them beside their
package under `*/src/test/java` and name methods by behaviour
(`hybridOfflineProfileUsesDottedNameForUuid`, `serverCommandsListIsNotTreatedAsServerEntry`).

Commits use concise imperative subjects, optionally with a `feat:`/`docs:` scope or an issue
reference. Keep each commit cohesive.
