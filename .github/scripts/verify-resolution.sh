#!/usr/bin/env bash
# Decides whether an upstream update that Claude resolved in CI is finished, the way a reviewer
# would before merging it, and doing the part Claude is not allowed to: the build. Claude never
# runs Gradle (waiting for it and reading its output would cost tokens; here it costs only GitHub's
# CPU), so this is the only place its work is built.
#
# Usage: verify-resolution.sh --out DIR --base SHA --reasons FILE --verdict FILE --notes FILE
#                             [--contains SHA]...
#   --base      main before the update: it must still be an ancestor, so nothing was rebased.
#   --contains  commits the result must still contain (the upstream merge, the tip it merged).
#   --reasons   what the update stopped for, one per line; the verdict must answer each one.
#   --verdict   the JSON Claude wrote: {"reasons": [{"reason", "status", "detail"}], "summary"}.
#   --notes     docs/releases/<version>.md, which must carry no MANUAL comment.
#
# Checks, all of them every time so that one round can fix everything at once:
#   - no merge, rebase or cherry-pick in progress, and nothing left uncommitted
#   - no conflict markers in any tracked file
#   - the base and every --contains commit are ancestors of HEAD
#   - .github/ is exactly as it was at the base: upstream's CI is never taken, and the scripts doing
#     this checking are not something the resolution may change
#   - the notes exist, name the version in gradle.properties, and have no MANUAL left
#   - the verdict marks every reason resolved and has a summary
#   - ./gradlew build passes, run with --continue so every failing task is reported at once
#     (VERIFY_BUILD overrides the command, for testing this script)
#   - the jar starts: smoke-test.sh (VERIFY_SMOKE overrides it)
#
# Writes into DIR: checks.log, build.log, smoke.log, failed (the stages that failed, one per line)
# and failure.txt (extract-failure.py's summary of them, which is what the next round is given).
# Exits 0 when everything passes, 1 otherwise.
set -uo pipefail

out="" base="" reasons="" verdict="" notes=""
contains=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2" ;;
    --base) base="$2" ;;
    --reasons) reasons="$2" ;;
    --verdict) verdict="$2" ;;
    --notes) notes="$2" ;;
    --contains) contains+=("$2") ;;
    *) echo "verify-resolution: unknown argument $1" >&2; exit 2 ;;
  esac
  shift 2
done
for required in out base reasons verdict notes; do
  if [ -z "${!required}" ]; then
    echo "verify-resolution: --${required} is required" >&2
    exit 2
  fi
done

here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "${out}"
checks="${out}/checks.log"
: > "${checks}"
: > "${out}/failed"
rm -f "${out}/build.log" "${out}/smoke.log" "${out}/failure.txt"
fail() { echo "- $*" >> "${checks}"; }

# State of the repository.
git_dir="$(git rev-parse --absolute-git-dir)"
for state in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply; do
  if [ -e "${git_dir}/${state}" ]; then
    fail "A git operation is still in progress (${state} exists). Conclude it with git commit; do not abort it."
  fi
done
dirty="$(git status --porcelain)"
if [ -n "${dirty}" ]; then
  fail "Uncommitted changes, which would not be published: $(echo "${dirty}" | head -n 10 | tr '\n' ';' | sed 's/;$//'). Commit them, or restore them if they are not meant to be there."
fi
# The working tree of every tracked file, so markers count whether or not they were committed.
markers="$(git grep -nE '^(<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)( |$)' -- . | head -n 20)"
if [ -n "${markers}" ]; then
  fail "Conflict markers are still in these tracked files: $(echo "${markers}" | tr '\n' ';' | sed 's/;$//')"
fi
if ! git merge-base --is-ancestor "${base}" HEAD; then
  fail "main before the update (${base}) is no longer an ancestor of HEAD: history was rewritten. Upstream is merged, never rebased, squashed or amended (docs/development/updating-upstreams.md)."
fi
# Upstream's CI never applies here (updating-upstreams.md), and these are the scripts that judge the
# result, so .github/ ends exactly as main had it.
github="$(git diff --name-only "${base}" HEAD -- .github | head -n 10)"
if [ -n "${github}" ]; then
  fail "Files under .github/ differ from main before the update: $(echo "${github}" | tr '\n' ';' | sed 's/;$//'). Keep the fork's: git checkout ${base} -- <file> (or git rm a file upstream added), and commit."
fi
for sha in "${contains[@]}"; do
  if ! git merge-base --is-ancestor "${sha}" HEAD; then
    fail "Commit ${sha} is no longer in HEAD's history: the upstream merge was rewritten or dropped. It has to stay a real merge commit."
  fi
done

# The release notes.
version="$(grep '^forkVersion=' gradle.properties | cut -d= -f2)"
if [ ! -f "${notes}" ]; then
  fail "The release notes ${notes} do not exist."
else
  if [ "$(basename "${notes}" .md)" != "${version}" ]; then
    fail "gradle.properties says forkVersion=${version}, but the notes are $(basename "${notes}"); release.yml only uses notes named after forkVersion."
  fi
  manual="$(grep -n 'MANUAL' "${notes}" | head -n 10)"
  if [ -n "${manual}" ]; then
    fail "${notes} still has MANUAL comments; replace each with what was found and what was done: $(echo "${manual}" | tr '\n' ';' | sed 's/;$//')"
  fi
fi

# Claude's verdict, against the reasons it was given.
if ! verdict_problems="$(python3 -I - "${verdict}" "${reasons}" 2>&1 <<'PY'
import json, sys
from pathlib import Path
path, reasons_path = sys.argv[1], sys.argv[2]
reasons = [l.strip() for l in Path(reasons_path).read_text(encoding="utf-8").splitlines() if l.strip()] \
    if Path(reasons_path).is_file() else []
try:
    verdict = json.loads(Path(path).read_text(encoding="utf-8"))
except FileNotFoundError:
    sys.exit(f"The verdict file {path} was not written.")
except ValueError as error:
    sys.exit(f"The verdict file {path} is not valid JSON: {error}")
problems = []
answers = verdict.get("reasons") if isinstance(verdict, dict) else None
if not isinstance(answers, list):
    sys.exit("The verdict has no \"reasons\" list.")
if len(answers) != len(reasons):
    problems.append(f"The verdict answers {len(answers)} reason(s) but there are {len(reasons)}; "
                    "answer each one, in order.")
for number, answer in enumerate(answers, 1):
    status = answer.get("status") if isinstance(answer, dict) else None
    if status != "resolved":
        problems.append(f"Reason {number} is marked {status!r}, not \"resolved\".")
if not str(verdict.get("summary", "")).strip():
    problems.append("The verdict has no summary.")
if problems:
    sys.exit(" ".join(problems))
PY
)"; then
  fail "${verdict_problems}"
fi

if [ -s "${checks}" ]; then
  echo checks >> "${out}/failed"
fi

# The build, and the jar it produces. Run even when a check above failed, so the round that follows
# sees every problem at once rather than one per round.
started="$(date +%s)"
build_cmd="${VERIFY_BUILD:-./gradlew --console=plain --continue build}"
echo "verify-resolution: ${build_cmd}"
if ${build_cmd} > "${out}/build.log" 2>&1; then
  smoke_cmd="${VERIFY_SMOKE:-${here}/smoke-test.sh proxy/build/libs/HybridAuth-${version}.jar}"
  echo "verify-resolution: ${smoke_cmd}"
  if ! REPO_ROOT="$(pwd)" ${smoke_cmd} > "${out}/smoke.log" 2>&1; then
    echo smoke >> "${out}/failed"
  fi
else
  echo build >> "${out}/failed"
fi

if [ ! -s "${out}/failed" ]; then
  echo "verify-resolution: everything passed."
  exit 0
fi

args=(--root . --since "${started}")
grep -qx checks "${out}/failed" && args+=(--checks "${checks}")
grep -qx build "${out}/failed" && args+=(--build "${out}/build.log")
grep -qx smoke "${out}/failed" && args+=(--smoke "${out}/smoke.log")
python3 -I "${here}/extract-failure.py" "${args[@]}" > "${out}/failure.txt"
echo "verify-resolution: failed ($(paste -sd, "${out}/failed")):"
cat "${out}/failure.txt"
exit 1
