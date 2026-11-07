---
name: releases-and-upstream
description: "How releases and upstream updates run themselves in HybridAuth: release.yml releases every change reaching main with the version taken from commit subjects, and upstream.yml merges Velocity and NanoLimbo hourly. Use whenever the task pushes or merges to main, chooses a commit subject, bumps forkVersion, writes release notes, touches .github/workflows or .github/scripts, or the user asks to resolve, review or finish an upstream update, an upstream-update issue or branch, even if they just say 'update Velocity' or 'why did the release not go out'."
---

<!-- Moved from CLAUDE.md § Releases and upstream updates run themselves on 2026-11-07 -->

## Releases and upstream updates run themselves

Two workflows do what used to be done by hand. Know what they do before pushing to `main`, because
**a push to `main` is a release**.

**`.github/workflows/release.yml` releases every change that reaches `main`** — a merged pull
request, a direct push — except one touching only `docs/`, `.md` files, `.github/`, `.claude/` or
`.gitignore`/`.gitattributes`, whose jar would
be identical. The version comes from the commit subjects since the last stable tag: `feat:` is a
minor, `type!:` or `BREAKING CHANGE` a major, anything else a patch. So the subject you write *is*
the version decision: use `feat:` for a feature, and never `feat:` for a fix. It builds first, then
commits `forkVersion` and the notes (from `docs/releases/TEMPLATE.md`) back to `main` as
`chore: release <version>`, tags, and publishes the jar.

To release with **hand-written notes** — anything an operator should read about — put
`docs/releases/<version>.md` and `forkVersion=<version>` in the same pull request; that version and
those notes are used as they are. A hyphenated `forkVersion` (`1.4.0-beta.1`) on any branch is a
pre-release for testing before a merge, deleted when its stable version comes out. Full detail:
`docs/development/releasing.md`.

**`.github/workflows/upstream.yml` checks both upstreams every hour** (`git ls-remote`, seconds, no
build) and only when one has commits `main` lacks does it merge them. It resolves the one mechanical
conflict (`gradle.properties` / `libs.versions.toml`, key by key), runs the review checks in
`.github/scripts/review-checks.sh`, builds, starts the jar, and — only if all of that passes — pushes
to `main` and releases a patch with notes from `docs/releases/TEMPLATE-upstream.md` saying it was
automatic. It never redoes the same upstream commits: the issue and a commit on the
`upstream-update/<date>` branch carry `<!-- upstream velocity=<sha> limbo=<sha> -->`, and an open
issue or such a branch with the current marker means nothing happens — so it works with the
repository's Issues switched off too.

**Switched off since November 2026, by the owner's decision:** Claude in CI runs only when the
repository variable `UPSTREAM_CLAUDE` is `true`. Until then, an update that needs judgment waits on
`upstream-update/<date>` and its issue for a person or a Claude session (below).

When something needs judgment, `UPSTREAM_CLAUDE` is `true` and the `CLAUDE_CODE_OAUTH_TOKEN` secret is set, **Claude resolves it
in the same run, on the runner**, with the prompt in **`.github/claude/resolve-upstream.md`** (edit
that file to change what Claude in CI is told). Claude edits and commits only — no Gradle, no push,
no token. `claude-rounds.sh` loops up to three rounds: after each, `verify-resolution.sh` builds,
starts the jar and checks the result, and `extract-failure.py` hands only the relevant error to the
next round. A passing round is pushed to `main` and released, and the issue gets Claude's summary
and is closed. Otherwise, or without the secret, nothing reaches `main`: the merge waits on
`upstream-update/<date>` and an issue labelled `upstream-update` says what needs judgment and what
Claude tried.

**When asked to resolve an upstream update**, follow "When it stops for review" in
`docs/development/updating-upstreams.md`: deal with every reason in the issue, replace every
`MANUAL` comment in `docs/releases/<version>.md` with what was found and what was done, build and
start the jar, and merge into `main` through a pull request from the session branch. The release
then goes out with those notes.

The subscription token lasts one year: while Claude in CI is on, `claude-check.yml` tests it monthly and opens a `claude-token`
issue when it needs renewing (`claude setup-token`, then update the secret). GitHub disables
schedules after 60 days without commits, so the upstream `check` job re-enables them daily, and
`branch-cleanup.yml` deletes branches whose commits are all in `main`.

Pushes made with `GITHUB_TOKEN` start no workflow — that is why the release commit cannot loop, and
why `upstream.yml` starts `release.yml` with `workflow_dispatch`. The scripts in `.github/scripts/`
run locally too; test a change to one against real history before pushing it (the 4.2.0 merge,
`84bd8cc`, is the case that exercises every check).
