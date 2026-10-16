#!/usr/bin/env bash
# Says, in a few seconds and without building anything, whether either upstream has commits that
# are not on main yet. upstream.yml runs this every hour and only starts the real update when it
# says yes: GitHub cannot tell this repository when somebody else's repository gets a commit, so
# asking often and cheaply is the only way to react when Velocity does.
#
# Usage: upstream-check.sh [ref, default HEAD]   (run inside a clone with main's full history)
# Prints key=value lines for $GITHUB_OUTPUT:
#   velocity=<sha>        the tip of PaperMC/Velocity dev/4.0.0
#   limbo=<sha>           the tip of Nan1t/NanoLimbo main
#   velocity_new=true|false
#   limbo_new=true|false
#   new=true|false        either of the two
#   marker=<!-- upstream velocity=<sha> limbo=<sha> -->
#
# Velocity is merged, so its tip is new when it is not an ancestor of <ref>. A commit that is not
# even in the clone cannot be one, so nothing upstream has to be fetched to know that. NanoLimbo is
# a squashed subtree, so its tip is new when it differs from the git-subtree-split trailer of the
# last "Squashed 'limbo/upstream/'" commit.
#
# The marker names exactly what an update was made from. The issue and the release commit an
# update leaves behind carry it, which is how the next run knows it has already handled these
# commits and must not do it all again an hour later.
set -euo pipefail

ref="${1:-HEAD}"
velocity_url="${VELOCITY_URL:-https://github.com/PaperMC/Velocity.git}"
velocity_branch="${VELOCITY_BRANCH:-dev/4.0.0}"
limbo_url="${LIMBO_URL:-https://github.com/Nan1t/NanoLimbo.git}"
limbo_branch="${LIMBO_BRANCH:-main}"

tip() {
  local sha
  sha="$(git ls-remote "$1" "refs/heads/$2" | cut -f1)"
  if [ -z "${sha}" ]; then
    echo "::error::$1 has no branch $2." >&2
    exit 1
  fi
  echo "${sha}"
}

velocity="$(tip "${velocity_url}" "${velocity_branch}")"
limbo="$(tip "${limbo_url}" "${limbo_branch}")"

velocity_new=true
if git cat-file -e "${velocity}^{commit}" 2>/dev/null \
    && git merge-base --is-ancestor "${velocity}" "${ref}"; then
  velocity_new=false
fi

split="$(git log -1 --format=%B --grep="^Squashed 'limbo/upstream/'" "${ref}" \
  | sed -n 's/^git-subtree-split: *//p' | head -n 1)"
limbo_new=true
if [ "${split}" = "${limbo}" ]; then
  limbo_new=false
fi

new=false
if [ "${velocity_new}" = true ] || [ "${limbo_new}" = true ]; then
  new=true
fi

echo "velocity=${velocity}"
echo "limbo=${limbo}"
echo "velocity_new=${velocity_new}"
echo "limbo_new=${limbo_new}"
echo "new=${new}"
echo "marker=<!-- upstream velocity=${velocity} limbo=${limbo} -->"
