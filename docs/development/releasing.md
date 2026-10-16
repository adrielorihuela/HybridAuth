# Releasing

**Every change that reaches `main` is released.** Merge a pull request or push to `main`, and
`.github/workflows/release.yml` publishes a GitHub release with `HybridAuth-<version>.jar` and its
notes — nobody cuts releases by hand any more, and nothing has to be remembered for one to happen.

This page is how that works, and how to steer it when the default is not what you want.

## What happens on a push to main

1. **Is there anything to release?** A push that touches only documentation (`docs/`, any `.md`) or
   CI (`.github/`) starts no run at all: the jar would be identical to the last one. Those changes
   go out with the next real change.
2. **Which version?** `.github/scripts/next-version.sh` reads the commits since the last stable tag
   and takes the largest bump any of them asks for:

   | Commit subject | Bump | Example |
   | --- | --- | --- |
   | `feat: …`, `feat(portals): …` | minor | 1.3.0 → 1.4.0 |
   | `feat!: …`, any `type!:`, or a body with `BREAKING CHANGE` | major | 1.3.0 → 2.0.0 |
   | anything else (`fix:`, `chore:`, upstream's own commits, …) | patch | 1.3.0 → 1.3.1 |

   Commits that only touch documentation or CI are not counted, and nor are the workflow's own
   `chore: release` commits. So **the commit subject is how you choose the version**: write `feat:`
   when you add something.
3. **Build.** `./gradlew build` — compilation, Checkstyle, Spotless, every test. If it fails, nothing
   is committed or tagged and `main` is left exactly as it was.
4. **Notes.** `.github/scripts/render-notes.py` fills `docs/releases/TEMPLATE.md`: the changes
   grouped into New, Fixes, Code, Documentation, Build and CI, with Velocity's commits from an
   upstream merge listed apart; a link to every commit and to the full comparison; and a section
   saying the release was made automatically, from which push, with how many tests passing.
5. **Commit back.** `forkVersion` and `docs/releases/<version>.md` are committed to `main` as
   `chore: release <version>` by `github-actions[bot]`. That keeps `gradle.properties` naming the
   version that is out and `docs/releases/` holding every release's notes.
6. **Publish.** Tag `v<version>` on that commit, create the release with the jar and the notes, and
   delete any `<version>-*` pre-releases it replaces.

Runs never overlap: a second push waits for the first, then releases from the tip of `main`,
which already carries the first one's release commit.

## Writing the notes yourself

A release worth explaining — a new feature, anything an operator has to act on — deserves notes
like those of 1.2.0 and 1.3.0 rather than a list of commits. To cut one:

1. Write `docs/releases/<version>.md`: what is new and why, what came from upstream, what the fork
   fixed, a known limitation if there is one, and how to upgrade. Use the earlier ones as the model.
2. Set `forkVersion=<version>` in `gradle.properties`, in the same pull request.
3. Merge it. The workflow sees a `forkVersion` that has no tag yet and is newer than the last
   release, and releases **exactly that version with exactly those notes**. Nothing is computed and
   nothing is committed back.

## Trying a build before it is merged

Set a hyphenated version, such as `forkVersion=1.4.0-beta.1`, on your branch and push. It is
published as a **pre-release**, from any branch, which is how 1.3.0 was tried on a live server. Bump
the suffix (`-beta.2`) for the next attempt: a published version is never replaced. When the stable
`1.4.0` is released, `1.4.0-beta.*` and their tags are deleted.

Pushing a `v<version>` tag by hand still releases that tag, as long as it matches `forkVersion`.

## When it does not work

- **The run is red at "Build with Gradle".** The change does not build on a clean machine. Nothing
  was released; fix it and push again.
- **The run is red at "Commit the release to main".** The workflow could not push its release commit.
  Either `main` became protected — allow `github-actions` to push to it, or the automatic releases
  stop — or another push landed first, in which case that push's run releases both.
- **The release has no jar.** `fail_on_unmatched_files` makes that an error rather than an empty
  release, so look at the run: the jar name comes from `forkVersion`.
- **Run it again by hand:** Actions → Release → *Run workflow* on `main`. It releases whatever is
  unreleased, or says there is nothing to do.

## One thing GitHub does on purpose

Pushes made with the workflow's own `GITHUB_TOKEN` start no other workflow. That is what stops the
release commit from releasing itself in a loop. It is also why the [upstream
update](updating-upstreams.md#it-runs-by-itself), which pushes to `main` with that token, starts
`release.yml` explicitly with `workflow_dispatch` afterwards.
