# Updating the upstreams

This fork tracks two independent upstream projects. This page is the procedure for pulling changes
from either of them, and the reasoning behind the choices, so a future session does not have to
rediscover it.

| Remote | Repository | What it gives us | How it enters the tree |
| --- | --- | --- | --- |
| `upstream` | [PaperMC/Velocity](https://github.com/PaperMC/Velocity) | The proxy itself — almost the whole tree | Merged into `main` |
| `limbo-upstream` | [Nan1t/NanoLimbo](https://github.com/Nan1t/NanoLimbo) | The authentication server, at `limbo/upstream/` | `git subtree pull` |

## The short version

```bash
./gradlew updateUpstreams
```

That is the whole procedure. It adds the two remotes if they are missing, merges Velocity, pulls
the NanoLimbo subtree with the exact flags it needs, and prints the checks that come afterwards.
It stops on anything unsafe rather than guessing, and it **never pushes** — an update nobody has
compiled or started is not something to publish.

| Task | What it does |
| --- | --- |
| `./gradlew addUpstreams` | Adds `upstream` and `limbo-upstream`. Idempotent; a fresh clone needs no other preparation. |
| `./gradlew updateVelocity` | Fetches and merges `upstream/dev/4.0.0`. |
| `./gradlew updateAuthServer` | `git subtree pull` for `limbo/upstream`. |
| `./gradlew updateUpstreams` | Both, in order. |

It refuses to start when the working tree is dirty, when a merge is already in progress, or when
you are not on `main` — each of those either loses work or leaves a history the next update cannot
follow. On a conflict it **leaves the merge in progress** and lists the conflicted files: aborting
it for you would throw away a resolution you might be halfway through.

The tasks live in [`gradle/upstreams.gradle.kts`](../../gradle/upstreams.gradle.kts), applied from
the root build script in one line so that upstream-owned file stays trivial to merge.

**One wrinkle.** If the conflict lands in `gradle/libs.versions.toml` — the most likely file of all
— Gradle itself will not start again until you have resolved it, because the version catalog is
parsed before any task runs. That costs nothing in practice: every recovery command is plain git
anyway (`git add`, `git commit`, `git merge --abort`), and the rest of this page is what to do.

The rest of this page is what those tasks do, and what to do when one of them stops.

## Doing it by hand

If the remotes are missing (a fresh clone only has `origin`):

```bash
git remote add upstream https://github.com/PaperMC/Velocity.git
git remote add limbo-upstream https://github.com/Nan1t/NanoLimbo.git
```

## How this history is shaped

Two facts decide everything below. Check them before deviating.

**`main` is a linear continuation of upstream Velocity.** It branches from `dev/4.0.0` at
`00759e52`, with the fork's own commits on top. There is no squashed import — upstream's full
history is our history, which is what makes merging cheap.

**The NanoLimbo subtree was added with a merge commit.** `67aa6368` merges the squashed import
`21137ced`, whose message carries the trailers `git-subtree-dir: limbo/upstream` and
`git-subtree-split: <sha>`. **`git subtree pull` finds the previous import by searching history for
those trailers.** Lose that commit and the subtree can no longer be updated without re-importing by
hand.

## Updating Velocity — merge, do not rebase

```bash
git fetch upstream
git merge upstream/dev/4.0.0
```

Done once already, at `00759e52` → `4772ca30` (five commits). The only conflict was
`gradle/libs.versions.toml`, in two hunks of the same shape: upstream's version bumps against the
fork's added dependencies. Resolving it means keeping both sides. Expect that shape again.

`dev/4.0.0` is upstream's default branch and the one this fork is based on. `dev/5.0.0` exists and
is a different major line — do not merge it without deciding that deliberately.

**Why merge rather than rebase.** Rebasing is the instinct for a fork, and it is wrong here for two
independent reasons:

1. **It would break the subtree.** `git rebase` drops merge commits unless told otherwise, so
   `67aa6368` — the commit `git subtree pull` looks for — would disappear from history. Updating the
   authentication server afterwards would mean re-importing it by hand.
2. **`main` is published.** It carries tags and releases people have downloaded. Rebasing rewrites
   every commit hash and forces a `--force-with-lease` push, which diverges anyone who cloned.

Merging keeps both intact and is the normal way to maintain a long-lived fork of a live project.

### When conflicts appear

They will, and almost always in the same few files, because those are the ones the fork changes.
Resolve them by keeping *both* sides — take upstream's change and re-apply the fork's on top:

| File | What the fork added |
| --- | --- |
| `VelocityServer.java` | Starting and stopping the authentication server, registering the shortcut commands, reclaiming the auth commands after `ProxyInitializeEvent` |
| `VelocityConfiguration.java` | The `OfflineAuthConfig` record, the `comandos` list in `Servers`, the cross-checks in `validate()` |
| `default-velocity.toml` | The `[offline-auth]` section and the `comandos` list |
| `connection/client/InitialLoginSessionHandler.java` | The hybrid offline profile on HTTP 204 |
| `command/builtin/*.java` | The `InternalServers` filter in the server listings |
| `proxy/build.gradle.kts`, `gradle/libs.versions.toml` | The fork jar naming, and the bcrypt, SQLite and `:limbo` dependencies |

`docs/development/` explains why each of those exists. Read the relevant page before deciding a
conflict resolution — several of them encode a bug that was expensive to find.

### After merging

```bash
./gradlew build
```

Then run the shaded jar and confirm the authentication server still starts:

```
[auth]: Server started on /127.0.0.1:30065
```

Watch for these specifically, because a Velocity update can silently break them:

- **`StateRegistry` changes.** New protocol versions change packet IDs. The authentication server
  has its own copy of the protocol in the subtree, so both have to know the version.
- **Event API changes.** The whole authentication gate is built from events
  (`ServerPreConnectEvent`, `PlayerAvailableCommandsEvent`, `CommandExecuteEvent`,
  `PermissionsSetupEvent`). A signature change there breaks the gate quietly — the proxy still
  starts.
- **`getAllServers()` consumers.** If upstream adds a new place that lists servers, the
  authentication server will appear in it. Add the new call site to the `InternalServers` filter.

## Updating the authentication server

```bash
git fetch limbo-upstream
git subtree pull --prefix=limbo/upstream limbo-upstream main --squash
```

`--squash` and the `--prefix` must match exactly what the original import used, or the trailers will
not line up.

### Re-apply the local patches

The subtree is otherwise byte-identical to upstream. Only two files are patched, both marked in the
source with `HybridVelocity patch:`, and both are listed in [auth-server.md](auth-server.md):

- `server/Log.java` — logs through SLF4J instead of casting to a logback logger, and names the
  logger `auth`.
- `server/LimboServer.java` — an embedded mode that skips the interactive console, the JVM shutdown
  hook and `System.gc()`.
- `configuration/LimboConfig.java` — an `override` for the bind address and forwarding, installed
  before `load()` because an absent `infoForwarding` node makes it throw.

If a pull conflicts in one of those, keep the patch and re-apply it on top of upstream's change.
Never resolve a conflict by dropping the patch: each one is load-bearing and the reason is written
next to it.

### Then

- Bump `LIMBO_VERSION` in `limbo/src/main/java/ua/nanit/limbo/BuildConfig.java` to the new upstream
  version. That file is hand-written precisely so the subtree needs no build plugin.
- Compare upstream's `gradle/libs.versions.toml` with this repository's. The subtree compiles
  against *our* versions, so a dependency bump upstream may need mirroring — Netty especially, since
  both sides must agree.
- Check whether upstream's `settings.yml` gained options worth exposing in
  `proxy/src/main/resources/auth-server-settings.yml`. That template is ours; new upstream options
  do not appear in it automatically.

Never run `spotlessApply` expecting it to touch `limbo/upstream/`. Checkstyle is disabled and
Spotless excludes that path deliberately: `velocity-spotless` applies `licenseHeaderFile`, which
would rewrite NanoLimbo's GPL copyright notices to "Velocity Contributors".

## Releasing after an update

1. Bump `forkVersion` in `gradle.properties`. Leave `version` alone — it is the upstream Velocity
   API version that plugins compile against.
2. `./gradlew build`, then run the shaded jar and check the boot line and the authentication server.
3. Tag `v<forkVersion>` and push the tag.
4. Create the release with `HybridVelocity-<forkVersion>.jar`.
