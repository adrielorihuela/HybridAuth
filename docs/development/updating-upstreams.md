# Updating the upstreams

This fork tracks two independent upstream projects. This page is the procedure for pulling changes
from either of them, and the reasoning behind the choices, so a future session does not have to
rediscover it.

| Remote | Repository | What it gives us | How it enters the tree |
| --- | --- | --- | --- |
| `upstream` | [PaperMC/Velocity](https://github.com/PaperMC/Velocity) | The proxy itself — almost the whole tree | Merged into `main` |
| `limbo-upstream` | [Nan1t/NanoLimbo](https://github.com/Nan1t/NanoLimbo) | The authentication server, at `limbo/upstream/` | `git subtree pull` |

## It runs by itself

`.github/workflows/upstream.yml` does all of this **every hour**, and on demand from Actions →
*Upstream update* → *Run workflow*. It does what can be done with certainty, has Claude supply the
judgment when that is set up, and stops, without touching `main`, at anything neither can settle.

**Why hourly.** Velocity can go a month without a commit and then get several in a day. GitHub has
no event that tells this repository when somebody else's repository changes, so the workflow asks.
Its first job, `check`, needs no JDK or Gradle: `upstream-check.sh` runs `git ls-remote` against
both upstreams and compares — Velocity's tip is new if it is not in `main`'s history, NanoLimbo's
if it differs from the `git-subtree-split:` trailer of the last `Squashed 'limbo/upstream/'`
commit. When neither is new, the run is over in seconds. Only otherwise does the `update` job start.

**It never does the same commits twice.** An update that stops for review leaves a marker,
`<!-- upstream velocity=<sha> limbo=<sha> -->`, naming exactly what it was made from, in its issue's
description and in a commit on its `upstream-update/<date>` branch. While an open `upstream-update`
issue or such a branch carries the marker of the current tips, the hourly check does nothing at
all: no comment, no Claude, no build. When
upstream gets new commits the marker no longer matches, so the update is redone over the new
state and the issue's description rewritten. *Run workflow* with **force** redoes it regardless.

| Step | Automatic | Stops for review when |
| --- | --- | --- |
| Merge Velocity and pull NanoLimbo, through the Gradle tasks below | yes | — |
| Conflicts in `gradle.properties` / `libs.versions.toml` | resolved key by key (`resolve-known-conflicts.py`) | any other file conflicts, or those two hold anything but `key = value` changes both sides made differently |
| Review checks (`review-checks.sh`) | run | `config-version` moved; a new Minecraft version; the gate's events changed; new `getAllServers()` callers; upstream touched `.github/`; the respawn stopped being encode-only; NanoLimbo changed at all |
| `./gradlew build` | run | it fails |
| Start the jar (`smoke-test.sh`) | run | no boot line, no authentication server, or no `Done` |
| Release | the next **patch**, with notes from `docs/releases/TEMPLATE-upstream.md` saying it was automatic and why that was safe | — |

When every row passes, the merge goes to `main` with `chore: release <version> (automatic upstream
update)`, and `release.yml` publishes it. Nobody is involved, and the notes say so.

When any row stops it, what happens next depends on whether Claude is set up.

### Claude's part

With the repository secret `CLAUDE_CODE_OAUTH_TOKEN` set, the same run hands the update to Claude.
It runs **on the GitHub Actions runner** — the ephemeral machine doing the update, not anybody's
computer — as the Claude Code CLI in headless mode, on the Claude subscription the token belongs
to. Its instructions are [`.github/claude/resolve-upstream.md`](../../.github/claude/resolve-upstream.md),
which sends it to this page and to `CLAUDE.md` and is followed by the reasons this update stopped.

**What it may do.** Read and edit files, and run `git add`, `commit`, `status`, `diff`, `log`,
`show`, `rm` and `checkout`. Nothing else: no Gradle, no `git push`, no GitHub access (there is no
token in its environment, and the checkout keeps none on disk while it works). It must keep both
sides of every conflict, never rebase, squash or amend, leave `.github/` as `main` had it, replace
every `MANUAL` comment in the notes with what it found and did — saying the update was resolved by
Claude in CI — and finish by writing a verdict marking each reason `resolved` or `needs-human`.

**Claude does not build; GitHub does.** Building on the runner costs nothing, while having Claude
wait for Gradle and read its output costs tokens. So the work goes in rounds
(`.github/scripts/claude-rounds.sh`):

1. **Round 1.** Claude gets the reasons — the review checks that fired, a red build or smoke test
   with the failure already cut down, or a conflict — edits, commits, writes the verdict and stops.
2. **GitHub verifies** (`verify-resolution.sh`): `./gradlew build`, the smoke test, no `MANUAL`
   left, no conflict markers, nothing uncommitted, `.github/` unchanged, the upstream merge and the
   old `main` still in history (so nothing was rebased), and every reason `resolved` in the verdict.
3. **If that fails**, `extract-failure.py` reduces the logs to the part that says why — compiler
   errors with file and line, failing tests with their message and first stack lines, Checkstyle
   and Spotless violations, or what the smoke test missed — at most 150 lines, and that alone goes
   to Claude in the **next round**: "the build failed with this; fix only that".
4. **Up to three rounds.** A `needs-human` verdict ends them early, since more rounds would not
   change it.

If the update conflicted, the merge is left in progress for Claude (instead of aborted, as it is
without Claude), and before round 1 Claude only concludes it, as a real merge commit with both
parents. Then the review checks run on the merged tree and the notes are drafted, as for an update
that merged, and the rounds begin with those reasons.

The scripts that judge Claude's work, and its prompt, are copied before it starts and run from that
copy, so nothing it edits in the checkout changes how it is judged.

**When it merges by itself, and when it waits.** If a round passes, the update is **merged into
`main` and released without anyone**: pushed to `main`, `release.yml` dispatched with
`forkVersion` already bumped and Claude's notes, the open `upstream-update` issue gets Claude's
summary and is closed, and the superseded `upstream-update/*` branches are deleted. If three rounds do not pass, Claude says a reason needs a person, it cannot
conclude a conflicted merge, or `main` moved while it worked, nothing reaches `main`: its attempt
is pushed to the `upstream-update/<date>` branch (unless the merge is still unconcluded) and the
issue lists each round's errors and Claude's verdict, for a person to finish as below.

**How to enable it.** On a computer with the Claude Code CLI, logged in to a Claude Pro or Max
plan, run:

```bash
claude setup-token
```

and save the token it prints as a repository secret named **`CLAUDE_CODE_OAUTH_TOKEN`**, in the
repository's *Settings → Secrets and variables → Actions → New repository secret*. The next update
that stops for review uses it. To turn Claude off, delete the secret.

**The token lasts one year**, and a subscription token cannot be made to last longer. Two things
catch it running out before an update depends on it:

- `.github/workflows/claude-check.yml` asks Claude for a single word on the first of every month,
  and on demand (Actions → *Claude token check* → *Run workflow*). If the token is missing,
  expired or refused, it opens an issue labelled **`claude-token`** with the renewal steps; the
  next green run closes it. Run it once after setting or renewing the secret.
- If the token is refused during an update anyway, `claude-rounds.sh` stops at once with
  `auth-failed` instead of spending its rounds, and the `upstream-update` issue says to renew it.

To renew: run `claude setup-token` again and *Update* the same secret. The alternative that does
not expire is a Claude Console API key, saved as `ANTHROPIC_API_KEY` instead — valid until revoked,
but billed per use rather than covered by the plan; the workflows would need that secret passed to
Claude in place of the OAuth token.

**The schedules are kept alive.** In a public repository GitHub disables a scheduled workflow after
60 days without commits, without saying so — and both upstreams can be that quiet. Once a day the
`check` job re-enables `upstream.yml`, `claude-check.yml` and `branch-cleanup.yml` through the API,
which restarts that count.

### Without Claude, or when it does not get there

**Nothing reaches `main`**:

- If the merge was concluded, it is pushed to an `upstream-update/<date>` branch with `forkVersion`
  already bumped and `docs/releases/<version>.md` drafted in manual mode: the commits and the
  reasons are filled in, and `MANUAL` comments mark what is left to write. After Claude, the
  branch holds its attempt instead.
- If it conflicted, the merge is not kept: a merge with conflict markers is not pushed anywhere. The
  `upstream-update/<date>` branch then holds a single empty commit carrying the marker.
- An issue labelled **`upstream-update`** lists the reasons, what Claude tried, the run, the branch
  and the steps below. A later run that goes through closes it as superseded.
- **Issues are optional.** The report is also written to the run's summary page, and the marker that
  stops the hourly check from redoing the same upstream commits is kept on the `upstream-update/<date>`
  branch too (when nothing could be kept, on a branch holding a single empty commit with the
  marker). With the repository's Issues switched off everything still works; you only lose the
  issue as the place the report appears. A later update that goes through deletes these branches.

Each check exists because that change has broken this fork, or would have, while the build stayed
green. The sections further down say why for each.

### When it stops for review

This is the part for a person, or for Claude in a session (Claude in CI follows the same steps,
except that the workflow builds and merges for it). Asked to "resolve the upstream update", Claude:

1. **Reads the issue.** Every reason it lists has to be dealt with; none is a formality.
2. **Gets the merge.** From the `upstream-update/<date>` branch if there is one. If the update
   conflicted, on an up-to-date `main`: `./gradlew updateUpstreams`, and resolve the conflicts as
   [When conflicts appear](#when-conflicts-appear) says — keep both sides.
3. **Deals with each reason**, using this page and the one it points to. For example: a moved
   `config-version` means reading `OfflineAuthMigration`'s hand-back against the new number, as
   [After merging](#after-merging) describes; a new Minecraft version means checking whether
   NanoLimbo supports it yet.
4. **Finishes the notes** in `docs/releases/<version>.md`: replaces every `MANUAL` comment with what
   was found and what was done, and rewrites anything in the draft that is no longer true. The
   result should read like 1.3.0's notes, not like a log.
5. **Verifies**: `./gradlew build`, then starts the jar and watches for the boot line and
   `[auth]: Server started on /127.0.0.1:30065`.
6. **Merges into `main`** — a merge, never a squash or a rebase, for the reasons below. `release.yml`
   sees the bumped `forkVersion` and releases it with those notes. Then the issue is closed.

Claude's sessions can only push to their own branch, so the merge into `main` goes through a pull
request from that branch.

### When an update reached `main` but was not released

The automatic path pushes to `main` first and starts `release.yml` second, so a failure in between
leaves `forkVersion` and its notes on `main` with no tag. Later runs see the marker and do nothing,
so nothing retries it. Start **Release** by hand on `main` (Actions → Release → Run workflow):
`next-version.sh` finds an unreleased `forkVersion` and publishes it with the notes as they are.
That is how 1.3.2 went out: four updates in a row merged and then failed because `gh` was looking
for `release.yml` in PaperMC/Velocity (fixed with `GH_REPO` in `upstream.yml`). Since then the notes
list every upstream commit since the last tag, so an update that reuses an unreleased version adds
to its notes instead of replacing them.

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
git fetch --no-tags upstream
git merge upstream/dev/4.0.0
```

Done three times so far, and every time the conflict was the same *shape* in a different file:

| Merge | Conflict | What it was |
| --- | --- | --- |
| `00759e52` → `4772ca30`, five commits | `gradle/libs.versions.toml`, two hunks | Upstream's version bumps against the fork's added dependencies |
| → `db0a17ee`, four commits (Velocity 4.1.1) | `gradle.properties` | Upstream setting `version` against the fork's `forkName`/`forkVersion` block below it |
| → `84bd8cc`, twenty-four commits (Velocity 4.2.0, Minecraft 26.3) | `gradle.properties`, `libs.versions.toml`, `default-velocity.toml`, the migration list, `RespawnPacket` | The same shape, plus upstream reaching `config-version` 2.9 for real — see below |

They all resolve the same way: **keep both sides**. `version` is upstream's to set and the fork follows
it; `forkName` and `forkVersion` are ours and upstream knows nothing about them. Expect that shape
again — the conflicts here are almost never about logic, they are two projects editing adjacent
lines of the same file.

**Upstream's CI does not apply here.** Since 4.2.0 upstream runs on Namespace runners
(`namespace-profile-linux-default`, `nscloud-*` actions), which this repository has no access to.
Keep `.github/workflows/gradle.yml` on `ubuntu-24.04` when it conflicts.

**When upstream moves `config-version`, check `OfflineAuthMigration`.** It hands the key back to
2.8 when it finds one of the numbers the fork used to write there (2.9, 3.0, 3.2). Upstream reached
2.9 for real in 4.2.0, so the number alone stopped meaning anything, and the hand-back now also
requires the old string `ping-passthrough` that upstream's 2.9 migration removes. Walking a genuine
2.9 back made that migration run again on its own output. An upstream 3.0 would need the same
thought; `ConfigVersionHandBackTest` is where to prove it.

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
git fetch --no-tags limbo-upstream
git subtree pull --prefix=limbo/upstream limbo-upstream main --squash
```

`--squash` and the `--prefix` must match exactly what the original import used, or the trailers will
not line up.

**`--no-tags` is not optional.** NanoLimbo numbers its releases the same way this fork does, and a
plain `git fetch` brings all of its tags into this repository — twenty-two of them the first time,
including a `v1.2.1` that collided with this fork's own release tag. The collision is not loud: the
local tag simply points at a NanoLimbo commit, so the next release either refuses to tag or pushes
somebody else's commit under our version number. If it has already happened, the foreign tags are
the ones local-only:

```bash
comm -23 <(git tag | sort) <(git ls-remote --tags origin | grep -v "\^{}" | sed "s|.*refs/tags/||" | sort)
```

`./gradlew updateAuthServer` passes `--no-tags` for you.

### Re-apply the local patches

The subtree is otherwise byte-identical to upstream. Three files are patched, each marked in the
source with `HybridAuth patch:`, and all are listed in [auth-server.md](auth-server.md):

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

Nothing to do by hand: merging the update into `main` releases it. See
[releasing.md](releasing.md) for how the version and the notes are chosen, and for writing the
notes yourself, which an update that needed review should always get.
