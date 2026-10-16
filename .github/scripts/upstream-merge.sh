#!/usr/bin/env bash
# Merges both upstreams into the checked-out main the documented way, through the Gradle tasks in
# gradle/upstreams.gradle.kts (merge, never rebase; --no-tags; the subtree with its exact flags),
# and resolves the one conflict that is mechanical (resolve-known-conflicts.py).
#
# Usage: upstream-merge.sh [--keep-conflict] <output dir>
# Prints key=value lines for $GITHUB_OUTPUT:
#   status=uptodate|merged|conflict
#   base=<main before the update>
#   merge_head=<sha>  with --keep-conflict and status=conflict: what the merge in progress merges
# and writes into <output dir>:
#   conflicts.txt  files left in conflict (status=conflict), one per line
#   resolved.txt   files whose conflict was resolved mechanically
# By default a conflict that is not mechanical is aborted, leaving main as it was: a merge with
# conflict markers in it is not something to push anywhere. --keep-conflict leaves it in progress
# instead, for Claude to resolve in the same run (upstream.yml does that only when Claude is set
# up); nothing here pushes either way.
set -euo pipefail

keep=false
if [ "${1:-}" = "--keep-conflict" ]; then
  keep=true
  shift
fi
out="${1:?output dir}"
mkdir -p "${out}"
: > "${out}/conflicts.txt"
: > "${out}/resolved.txt"
here="$(cd "$(dirname "$0")" && pwd)"

base="$(git rev-parse HEAD)"
echo "base=${base}"
# The resolver needs the common ancestor inside each conflict.
git config merge.conflictStyle diff3

in_merge() { [ -f "$(git rev-parse --absolute-git-dir)/MERGE_HEAD" ]; }

# Runs one Gradle task; a conflict leaves a merge in progress, any other failure is an error.
step() {
  local task="$1" label="$2"
  if ./gradlew --console=plain --quiet "${task}" >&2; then
    return 0
  fi
  if ! in_merge; then
    echo "::error::${task} failed without leaving a merge in progress; see the log above." >&2
    exit 1
  fi
  git diff --name-only --diff-filter=U > "${out}/step-conflicts.txt"
  local files
  mapfile -t files < "${out}/step-conflicts.txt"
  if [ "${label}" = "velocity" ] \
      && python3 -I "${here}/resolve-known-conflicts.py" "${files[@]}" >&2; then
    git add -- "${files[@]}"
    git commit --no-edit --quiet
    cat "${out}/step-conflicts.txt" >> "${out}/resolved.txt"
    return 0
  fi
  cat "${out}/step-conflicts.txt" >> "${out}/conflicts.txt"
  if [ "${keep}" = true ]; then
    echo "merge_head=$(git rev-parse MERGE_HEAD)"
    echo "status=conflict"
    exit 0
  fi
  git merge --abort
  git reset --quiet --hard "${base}"
  echo "status=conflict"
  exit 0
}

step updateVelocity velocity
# A conflict in the NanoLimbo subtree is never mechanical: three of its files carry local patches.
step updateAuthServer limbo

if [ "$(git rev-parse HEAD)" = "${base}" ]; then
  echo "status=uptodate"
else
  echo "status=merged"
fi
