---
paths:
  - "proxy/src/main/java/com/velocitypowered/proxy/VelocityServer.java"
  - "proxy/src/main/java/com/velocitypowered/proxy/config/**"
  - "proxy/src/main/resources/default-velocity.toml"
  - "proxy/src/test/java/com/velocitypowered/proxy/config/**"
---

<!-- Moved from CLAUDE.md § Architecture › config-version belongs to upstream on 2026-11-07 -->

### config-version belongs to upstream

`config-version` counts **upstream Velocity's** migrations, and every upstream migration is gated
on being *below* a number. This fork used to write `3.2` into it while upstream sat at `2.8`,
which spends four numbers that are not ours: a Velocity release adding a migration at `2.9` would
have found `3.2` already there and skipped it, silently, on every installation at once. Nothing
fails; a setting simply never gets migrated.

Fork migrations therefore count on **`hybridauth-config-version`**, and `OfflineAuthMigration`
hands `config-version` back to upstream's value on any configuration still carrying the old
number. When merging an upstream release, check whether it moved `config-version` in
`default-velocity.toml` and follow it — that value is theirs to set.

<!-- Moved from CLAUDE.md § Architecture › Wiring on 2026-11-07 -->

### Wiring

`VelocityServer` is the composition root: it constructs the command manager, event manager,
plugin manager, scheduler, `ServerMap` and `ConnectionManager`, then `start()` registers
built-in commands, loads the configuration, registers servers, loads plugins, fires
`ProxyInitializeEvent` and binds. Note the ordering constraint: built-in commands are
registered *before* the config is loaded, so anything that depends on configuration (like the
server shortcut commands) must be registered after the server-registration loop.
`reloadConfiguration()` is the mirror image for `/hybridauth reload` and must be updated
whenever startup gains configuration-derived state.

`VelocityConfiguration` deserializes `config/HybridAuth.toml` with night-config, using
`proxy/src/main/resources/default-velocity.toml` as the template written on first run.
Sub-tables (`[servers]`, `[forced-hosts]`, `[advanced]`, …) map to private static inner
classes. `validate()` is the place for cross-checks between sections, and
`config/migration/` holds `ConfigurationMigration` implementations that rewrite older configs
in place — add one when an existing key changes shape.

`api/` is the public plugin API and must stay source-compatible; `proxy/` implements it.
`native/` provides libdeflate/OpenSSL acceleration with pure-Java fallbacks, so nothing may
assume the native path is available. `limbo/` is vendored third-party source — see below.
