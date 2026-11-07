# CLAUDE.md

HybridAuth is a fork of [Velocity](https://github.com/PaperMC/Velocity), the Minecraft server proxy:
hybrid offline profiles, offline authentication on an in-process authentication server, per-server
shortcut commands, portal redirects and plugin auto-update. Most of the tree is upstream code.

## How to work in this repo

This project runs the LeanFlow coding preset. Every rule below cites its evidence in LeanFlow's research, part 5; rules marked (trial) are being measured in `state/experiments.json`.

**Who does the work**
1. Small and medium tasks (a few files, one phase): do them here. Subagents start cold and count against the same limits, so delegating small work costs more (Anthropic sub-agents docs; Opus 5 guide: "keep spawn counts low").
2. Reading a lot of code to find something: use `explorer` (Haiku) and ask for paths plus a short summary. The built-in Explore agent is pinned to Haiku too.
3. Large, self-contained tasks with heavy reading (a migration, a cross-cutting refactor, a codebase audit): hand them to `orchestrator` with a 5 to 10 line brief; it returns at most 15 lines. (trial E1)
4. Well-specified implementation can go to `implementer` (Sonnet) with a spec: goal, files, acceptance criteria, verification command.
5. Use `reviewer` (Opus High) for large or risky diffs only; for small changes, run the tests yourself (Opus 5.5 verifies its own work).

**Long output**
6. A hook saves long command output to `.claude/logs/` and shows the tail, the error lines and the log path. Grep the log for what you need; ask `log-reader` only for logs too large to grep usefully. (trial E2)

**When something fails: escalate automatically, without asking** (trial E6; ladder in `.claude/lean/escalation.json`)
7. First classify the failure. Environment failure (network, timeout, missing dependency, a flaky test that passes on rerun, disk, permissions): fix the environment or rerun once; do not escalate.
8. Capability failure (the agent's change still fails its acceptance check after it tried to fix it, or it says it cannot solve it): move one rung up the ladder in `escalation.json`, carrying the spec and the failure output. Raise effort within the same model first (it keeps the cache); change model only by launching a fresh subagent on the next rung, never by switching this session's model.
9. At the top rung, retry once. If it still fails, stop and tell the user what was tried at each rung and the last error. Offer a costlier option only if `escalation.json` → `ask_user_options` lists one (pairs the data shows beating the top rung); if the list is empty, say no measured option does better and suggest splitting or re-specifying the task.
10. Log every attempt as one line in `.claude/lean/events.jsonl`: `{"date","task","rung","model","effort","outcome":"pass|fail","failure":"env|capability|none"}`. This is LeanFlow's quality signal.

**Housekeeping**
11. Never turn on ultracode or workflows unless asked; when asked, subagents run on Haiku.
12. Suggest `/clear` when the next task is unrelated.
13. Once a week, at the start of the first session, run `python3 .claude/lean/lean_maint.py weekly` and act only on its last line (`ACTION NONE` or `ACTION RUN <skill>`).
14. `/init` in this project is the LeanFlow version (`.claude/skills/init/`).

Verification commands for this repo:

- `./gradlew :velocity-proxy:spotlessApply` — formatting; run before every commit.
- `./gradlew :velocity-proxy:test --tests "<fully.qualified.TestClass>"` — the tests for the code touched.
- `./gradlew build` — the full cycle: compile, Checkstyle, Spotless check, all tests, distributions.
- `.github/scripts/smoke-test.sh proxy/build/libs/HybridAuth-<forkVersion>.jar` — after `build`, for anything touching start-up, login, configuration or the authentication server: the jar must boot and log `[auth]: Server started on /127.0.0.1:30065`.
- Changes to `.github/scripts/`: `bash -n` on the script, then run it against real history (the 4.2.0 merge, `84bd8cc`, exercises every check).

## What this repository is

**Read the docs before changing anything in login, configuration, commands or the `:limbo`
module.** Most of what is written there exists to stop a specific mistake being made twice.

| Where | What it answers |
| --- | --- |
| `docs/guide/` | How an operator uses the fork. Start at `docs/guide/getting-started.md`. |
| `docs/development/README.md` | Why the fork is built this way: specification, the failed attempt, protocol research, architecture. |
| `docs/development/updating-upstreams.md` | **How to pull changes from Velocity or NanoLimbo** — merging and rebasing are not interchangeable here. Runs hourly by itself; also says what to do when it stops for review. |
| `docs/development/releasing.md` | **How releases happen**: every change reaching `main` is released, versioned from commit subjects. |
| `docs/development/auth-server.md` | The vendored subtree, its three local patches, and why each exists. |

Fork-specific behaviour (each guide in `docs/guide/` explains it):

- **Hybrid offline profiles** (`connection/client/InitialLoginSessionHandler.java`): on a 204 from
  Mojang's session server the player is accepted with a `.`-suffixed name and an offline UUID from it.
- **Offline authentication** (`proxy/.../auth/`, `[offline-auth]`, on by default): unauthenticated
  offline players wait on the authentication server until they `/register` or `/login`.
- **The authentication server** (`:limbo`, `auth/AuthServer.java`): vendored NanoLimbo run
  in-process. "Limbo" is only upstream's name; everything operator-facing says authentication server.
- **Per-server shortcut commands** (`command/builtin/ServerShortcutCommand.java`, registered from
  `VelocityServer#registerServerCommands`): the `comandos` list in `[servers]`.
- **Portal redirects** (`[portales]`): **the backend's respawn is registered encode-only in
  `StateRegistry`**, so it reaches `BackendPlaySessionHandler#handleUnknown` as a raw buffer and is
  read there; `handle(RespawnPacket)` alone never fires (how 1.3.0-beta.1 shipped broken). The
  respawn is still forwarded. The section uses multi-line inline tables, so the configuration is
  parsed as TOML 1.1 (`config/Toml11Format.java`).
- **Plugin auto-update** (`plugin/update/`, `[plugins]`): Hangar, Modrinth, GitHub, GeyserMC and
  LuckPerms, in parallel, immediately before `loadPlugins()`.

The configuration is `config/HybridAuth.toml`; `ConfigurationLocation` moves `proxy.toml` and
upstream's `velocity.toml` (in `config/` or the root) into place with `forwarding.secret`.
`hybridvelocity.toml` stopped being read in 1.3.1. The secret is **moved and never regenerated**: a
fresh one is rejected by every backend on modern forwarding with no error to explain it. The
`.toml` extension is load-bearing (night-config picks its parser from it).

Permission nodes are `hybridauth.command.*`, with upstream's `velocity.command.*` read as a fallback
when the `hybridauth` node is unset.

**Hard constraints**

- Keep diffs against upstream narrow so upstream merges stay manageable.
- `main` continues upstream's `dev/4.0.0`: upstream changes are **merged, never rebased** (the
  NanoLimbo subtree's import merge commit must survive, and published history must not be rewritten).
- **A push to `main` is a release**, and the commit subject is the version decision: `feat:` a minor,
  `type!:`/`BREAKING CHANGE` a major, anything else a patch — never `feat:` for a fix. Only changes
  limited to `docs/`, `.md`, `.github/`, `.claude/` or `.gitignore`/`.gitattributes` are not released.
- Renames by search and replace have broken compatibility twice. `LEGACY_PERMISSION_PREFIXES`
  (`BuiltinCommandUtil`) and `PREVIOUS_NAMES` (`ConfigurationLocation`) hold old names on purpose
  and must never be touched; nor the `velocity.command.*` translation keys, the `com.velocitypowered`
  packages, the `velocity-api`/`velocity-proxy` module names, the `velocity:player_info` channel or
  the `"velocity"` plugin id. Never bring back the `hybridvelocity` name or the `/ha`, `/hv` aliases.
- `config-version` belongs to upstream; fork migrations count on `hybridauth-config-version`.
- `limbo/upstream/` is a pristine subtree: no reformatting, local changes only as marked
  `HybridAuth patch:` blocks (a licensing requirement, not style).
- Blocking work never runs on a Netty event loop.

## Committing is not optional

**Commit every change, and push every commit** — as each piece is finished, not at the end. The
commits are the record of *why*, and **everything not pushed exists on exactly one computer**: a push
is the backup, and lets progress be watched live. `git commit`, then `git push`, every time; never
batch them or leave commits unpushed. **This includes updating this file**: `/init` always ends in a
commit and a push.

## Commands

The Gradle wrapper is the entry point (`./gradlew.bat` on Windows); Java 25 toolchain, JDK 25 in CI.

```bash
./gradlew build                                   # compile, Checkstyle, Spotless check, tests, distributions
./gradlew :velocity-proxy:test --tests "com.velocitypowered.proxy.config.ServersConfigTest"  # one class
./gradlew :velocity-proxy:spotlessApply           # formatting; the build fails on style violations
./gradlew :velocity-proxy:runShadow               # run locally; generates config, secret and lang/ in proxy/run/
./gradlew updateUpstreams                         # update both upstreams by hand; never pushes
```

Modules: `:velocity-api`, `:velocity-proxy`, `:velocity-native`, `:limbo`. `updateUpstreams`
(steps: `addUpstreams`, `updateVelocity`, `updateAuthServer`; in `gradle/upstreams.gradle.kts`)
fetches with `--no-tags` — load-bearing, a plain fetch imported NanoLimbo's colliding `v1.2.1` — and
refuses a dirty tree, a merge in progress or a branch other than `main`. `proxy/build/libs/` holds
`HybridAuth-<forkVersion>.jar` (the runnable one, attached to releases) and `-thin.jar` (feeds
`shadowJar` only).

**Versioning: two versions, not interchangeable.** `version` in `gradle.properties` is the **upstream Velocity
API version** (the Maven coordinates plugins compile against): never bump it for a fork release.
`forkName`/`forkVersion` name the distributable (jar name, manifest, `Booting up HybridAuth
<forkVersion>`); the release workflow sets `forkVersion`, by hand only to release with hand-written
notes.

**Java 25 is a hard requirement**: it is also the bytecode target. The exception is
`proxy/src/bootstrap/`, the jar's `Main-Class`, compiled with `options.release = 8`: it checks the
Java version and explains the problem, reaches the real entry point by reflection, and must stay
free of anything newer than Java 8.

## Architecture in brief

A player connection is a Netty channel in `MinecraftConnection`, delegating every packet to one
active `MinecraftSessionHandler`; `StateRegistry` maps packet IDs per state and version. Each player
also has a backend connection with its own handler chain in `connection/backend/`. `VelocityServer`
is the composition root and `start()`'s order matters (built-in commands before the config;
configuration-derived state mirrored in `reloadConfiguration()`). `api/` must stay
source-compatible; `native/` always has pure-Java fallbacks. The detail for each area loads from
`.claude/rules/` when its files are in play.

## Conventions

Google Java Style via Checkstyle and Spotless: two-space indentation, braces on the same line, sorted
imports, no unused imports. Preserve nullness annotations (Checker Framework) and the asynchronous
event-loop behaviour of anything you touch. Tests are JUnit Jupiter (Mockito in the proxy module),
beside their package under `*/src/test/java`, named by behaviour
(`hybridOfflineProfileUsesDottedNameForUuid`). Commits: concise imperative subjects, optionally
`feat:`/`docs:` or an issue reference; one cohesive commit per change, each pushed.

## Where things live

- `.claude/skills/releases-and-upstream/SKILL.md` — how `release.yml` and `upstream.yml` work, what to do when an update stops for review, the token, the scripts (moved from § Releases and upstream updates run themselves).
- `.claude/rules/connections.md` — connection lifecycle, handler chains, `StateRegistry` (§ Connection lifecycle).
- `.claude/rules/configuration-and-wiring.md` — `config-version` and fork migrations; `VelocityServer` wiring, `VelocityConfiguration` (§ config-version, § Wiring).
- `.claude/rules/renames.md` — the legacy literals and why they must survive renames (§ Renames).
- `.claude/rules/plugin-update.md` — `PluginUpdater`, sources, checksums, the behaviours established against live APIs (§ Plugin auto-update).
- `.claude/rules/auth.md` — the authentication server subtree and the offline-auth gate (§ The authentication server, § Offline authentication).
- `.claude/rules/commands-and-l10n.md` — Brigadier commands, the permission rule, translations (§ Commands and permissions, § Translations).
- `.claude/agents/` — `orchestrator` (Opus), `explorer` and `Explore` (Haiku), `implementer` (Sonnet), `reviewer` (Opus High), `log-reader` (Haiku).
- `.claude/lean/` — `escalation.json` (the ladder), `events.jsonl` (every attempt), `lean_maint.py` (weekly maintenance); `.claude/hooks/lean-logs.py` digests long output into `.claude/logs/`.
- `.claude/skills/init/` — this project's `/init`; `.claude/lean-check.md` — the weekly check.
- `.github/claude/resolve-upstream.md` — the prompt for Claude in CI (switched off unless `UPSTREAM_CLAUDE` is `true`).
- `CLAUDE.md.bak` — the previous, 444-line version of this file (not tracked; `git log -p CLAUDE.md` has it too).
