#!/usr/bin/env bash
# Commits the release of an upstream update on top of its merge: forkVersion bumped to the next
# patch, and docs/releases/<version>.md rendered from docs/releases/TEMPLATE-upstream.md.
#
# Usage: upstream-draft.sh auto|manual <work dir>
# Environment: VERSION, PREVIOUS (the last stable tag), BASE (main before the update), MARKER (the
# <!-- upstream ... --> line naming what was merged, which the commit carries so later runs can
# tell this update apart from a newer one).
# Reads <work dir>/reasons.txt (manual) and <work dir>/resolved.txt.
#
# auto:   the update needed nobody; the notes say so and why that was safe.
# manual: the update stopped for review; the notes are drafted with MANUAL comments for whoever
#         resolves it (a person, or Claude in upstream.yml) to replace.
# Commits, never pushes.
set -euo pipefail

mode="${1:?auto or manual}"
work="${2:?work dir}"
: "${VERSION:?}" "${PREVIOUS:?}" "${BASE:?}" "${MARKER:?}"
here="$(cd "$(dirname "$0")" && pwd)"
notes="docs/releases/${VERSION}.md"

sed -i "s/^forkVersion=.*/forkVersion=${VERSION}/" gradle.properties
args=(upstream --mode "${mode}" --version "${VERSION}" --previous "${PREVIOUS}" --base "${BASE}"
  --velocity-ref upstream/dev/4.0.0 --resolved "${work}/resolved.txt")
if [ "${mode}" = "manual" ]; then
  args+=(--reasons "${work}/reasons.txt")
fi
REPO_ROOT="$(pwd)" python3 -I "${here}/render-notes.py" "${args[@]}" > "${notes}"
git add gradle.properties "${notes}"

if [ "${mode}" = "auto" ]; then
  git commit --quiet -m "chore: release ${VERSION} (automatic upstream update)" \
    -m "The upstream update merged cleanly, built, started, and fired no review check, so it is released without anyone. See ${notes}." \
    -m "${MARKER}"
else
  git commit --quiet -m "chore: release ${VERSION} (upstream update, needs review)" \
    -m "Drafted by the upstream update, which stopped for review. Not on main until somebody, or Claude in the same run, finishes it." \
    -m "${MARKER}"
fi
echo "Committed ${notes} and forkVersion=${VERSION} (${mode})."
