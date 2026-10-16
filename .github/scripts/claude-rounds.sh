#!/usr/bin/env bash
# Has Claude resolve an upstream update that stopped for review, in rounds, with GitHub building in
# between. Called by upstream.yml on the runner; never pushes, and never gives Claude a way to.
#
#   0. If the merge is still in progress (it conflicted), Claude first resolves the conflict and
#      concludes it as a merge commit, and nothing else. Then the review checks run on the merged
#      tree and the release is drafted (upstream-draft.sh manual), as for an update that merged.
#      Two attempts; this is not one of the build rounds.
#   1. Claude gets .github/claude/resolve-upstream.md, the reasons the update stopped, and, if the
#      merged tree already failed to build or start, that failure cut down by extract-failure.py.
#      It edits, commits and writes a verdict. It does not run Gradle.
#   2. verify-resolution.sh builds, starts the jar and checks the result.
#   3. If that failed, the next round gets only the failure ("fix only that"). Up to MAX_ROUNDS.
#   A verdict marking any reason needs-human stops the rounds: more of them would not change it.
#
# Everything that judges Claude's work (the scripts, the prompt) is copied before Claude runs and
# used from that copy, so nothing Claude edits in the checkout can change how it is judged.
#
# Usage: claude-rounds.sh <work dir>   (from the repository root)
# Environment:
#   BASE, VERSION, PREVIOUS, MARKER   as for upstream-draft.sh
#   STATUS                            merged | conflict (upstream-merge.sh's)
#   MERGE_HEAD                        for STATUS=conflict: what the merge in progress merges
#   CLAUDE_CODE_OAUTH_TOKEN           passed through to Claude
#   MAX_ROUNDS (3), CLAUDE_MAX_TURNS (100), CLAUDE_TIMEOUT (40m), CLAUDE_MODEL (CLI default)
#   CLAUDE_CMD                        default: npx -y @anthropic-ai/claude-code
#   VERIFY_BUILD, VERIFY_SMOKE        passed through to verify-resolution.sh (for testing)
# Reads <work dir>/reasons.txt, conflicts.txt, resolved.txt and initial-failure.txt (optional).
# Writes <work dir>/claude/: result (passed | failed | needs-human | unresolved-conflict |
# auth-failed), rounds,
# report.md (for the issue), out/verdict.json, and per call its prompt, Claude's output and the
# verification logs.
set -euo pipefail

work="$(realpath "${1:?work dir}")"
: "${BASE:?}" "${VERSION:?}" "${PREVIOUS:?}" "${MARKER:?}" "${STATUS:?}"
max="${MAX_ROUNDS:-3}"
dir="${work}/claude"
rm -rf "${dir}"
mkdir -p "${dir}/out" "${dir}/trusted"
cp -r .github/scripts "${dir}/trusted/scripts"
cp .github/claude/resolve-upstream.md "${dir}/trusted/prompt.md"
scripts="${dir}/trusted/scripts"
verdict="${dir}/out/verdict.json"
notes="docs/releases/${VERSION}.md"
reasons="${work}/reasons.txt"
touch "${reasons}" "${work}/resolved.txt"

allowed="Read,Edit,Write,Glob,Grep,Bash(git add:*),Bash(git commit:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git show:*),Bash(git rm:*),Bash(git checkout:*)"
disallowed="Bash(git push:*),Bash(git rebase:*),Bash(git reset:*),Bash(git merge:*),Bash(./gradlew:*),WebFetch,WebSearch"

finish() {
  echo "$1" > "${dir}/result"
  echo "claude-rounds: $1"
  exit 0
}

# Runs Claude once: $1 the prompt file, $2 where its output goes.
run_claude() {
  local -a cmd extra=()
  read -r -a cmd <<< "${CLAUDE_CMD:-npx -y @anthropic-ai/claude-code}"
  if [ -n "${CLAUDE_MODEL:-}" ]; then
    extra=(--model "${CLAUDE_MODEL}")
  fi
  echo "::group::Claude: $(basename "$(dirname "$1")")"
  set +e
  # No GitHub token reaches Claude: it has nothing to publish with.
  env -u GH_TOKEN -u GITHUB_TOKEN -u ACTIONS_RUNTIME_TOKEN -u ACTIONS_ID_TOKEN_REQUEST_TOKEN \
    timeout "${CLAUDE_TIMEOUT:-40m}" "${cmd[@]}" -p "$(cat "$1")" \
    --allowedTools "${allowed}" --disallowedTools "${disallowed}" \
    --max-turns "${CLAUDE_MAX_TURNS:-100}" --add-dir "${dir}/out" "${extra[@]}" \
    < /dev/null 2>&1 | tee "$2"
  local status="${PIPESTATUS[0]}"
  set -e
  echo "::endgroup::"
  echo "claude-rounds: Claude exited with ${status}"
  if [ "${status}" -ne 0 ] && grep -Eiq \
      'invalid api key|authentication_error|oauth token|token (has )?expired|login expired|please run /login|not logged in|\b401\b' "$2"; then
    auth_failed "$2"
  fi
}

# Claude never started working because the token was refused. Not a round, and not something a
# retry fixes: the token has to be renewed, and the issue says how.
auth_failed() {
  echo 0 > "${dir}/rounds"
  {
    echo "Claude could not start: the \`CLAUDE_CODE_OAUTH_TOKEN\` secret was refused. Tokens from"
    echo "\`claude setup-token\` last one year, so it has most likely expired. To renew it:"
    echo
    echo "1. On a computer with Claude Code, logged in with a Pro/Max plan, run \`claude setup-token\`."
    echo "2. Settings -> Secrets and variables -> Actions -> \`CLAUDE_CODE_OAUTH_TOKEN\` -> Update."
    echo "3. Actions -> Claude token check -> Run workflow, to confirm; then Actions -> Upstream update"
    echo "   -> Run workflow with force, or resolve this update by hand."
    echo
    echo '```'
    tail -n 15 "$1"
    echo '```'
  } > "${dir}/report.md"
  finish auth-failed
}

# The part of the prompt that is about this run. $1 is what the call is for: conflict or resolve.
context() {
  echo
  echo "## This run"
  echo
  echo "- The repository is the current directory, on \`main\` as the runner checked it out; you are"
  echo "  committing on top of it."
  echo "- \`main\` before the update (the base): \`${BASE}\`"
  echo "- Upstream Velocity merged from \`upstream/dev/4.0.0\` at \`$(git rev-parse upstream/dev/4.0.0 2>/dev/null || echo unknown)\`"
  echo "- The release: \`forkVersion=${VERSION}\` (already set), notes in \`${notes}\`, previous release \`${PREVIOUS}\`"
  echo "- The verdict file: \`${verdict}\`"
  if [ "$1" = "conflict" ]; then
    echo
    echo "### This call: conclude the merge, and nothing else"
    echo
    echo "The merge of \`${MERGE_HEAD:-MERGE_HEAD}\` is **in progress** and stopped on conflicts that no"
    echo "script can resolve with certainty. In these files:"
    echo
    sed 's/^/- `/; s/$/`/' "${work}/conflicts.txt"
    echo
    echo "Resolve every conflict as *When conflicts appear* in updating-upstreams.md says: keep both"
    echo "sides. For files under \`.github/\`, keep the fork's version (\`git checkout ${BASE} -- <path>\`)."
    echo "Then \`git add\` each file and conclude the merge with \`git commit --no-edit\`, which makes the"
    echo "merge commit with both parents. Do not make any other commit, and do not write the notes or"
    echo "the verdict yet: once the merge is concluded, the workflow runs the review checks on the"
    echo "merged tree, drafts the notes, and calls you again with every reason to deal with."
  else
    echo "- Round ${round} of ${max}."
    echo
    echo "### What the update stopped for (the reasons, in order)"
    echo
    awk 'NF { printf "%d. %s\n", ++n, $0 }' "${reasons}"
  fi
}

concluded() {
  local problems=()
  if [ -e "$(git rev-parse --absolute-git-dir)/MERGE_HEAD" ]; then
    problems+=("The merge is still in progress: resolve the remaining conflicts, git add them and git commit --no-edit.")
  fi
  if [ -z "$(git rev-list --parents "${pre_merge}..HEAD" | awk -v a="${pre_merge}" -v b="${MERGE_HEAD}" '$2 == a && $3 == b && NF == 3')" ]; then
    problems+=("There is no merge commit with parents ${pre_merge} and ${MERGE_HEAD} on top of main: the merge has to be concluded with git commit, as a merge.")
  fi
  local markers
  markers="$(git grep -nE '^(<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)( |$)' -- . | head -n 20 || true)"
  if [ -n "${markers}" ]; then
    problems+=("Conflict markers remain: $(echo "${markers}" | tr '\n' ';')")
  fi
  if [ -n "$(git status --porcelain)" ]; then
    problems+=("Uncommitted changes remain: $(git status --porcelain | head -n 10 | tr '\n' ';')")
  fi
  printf '%s\n' "${problems[@]}" > "$1"
  [ "${#problems[@]}" -eq 0 ]
}

# 0. A conflicted merge is concluded first.
if [ "${STATUS}" = "conflict" ]; then
  : "${MERGE_HEAD:?}"
  pre_merge="$(git rev-parse HEAD)"
  for attempt in 1 2; do
    call="${dir}/conflict-${attempt}"
    mkdir -p "${call}"
    {
      cat "${dir}/trusted/prompt.md"
      context conflict
      if [ "${attempt}" -gt 1 ]; then
        echo
        echo "### The previous attempt did not conclude the merge"
        echo
        cat "${dir}/conflict-$((attempt - 1))/problems.txt"
      fi
    } > "${call}/prompt.md"
    run_claude "${call}/prompt.md" "${call}/claude.log"
    if concluded "${call}/problems.txt"; then
      break
    fi
    echo "claude-rounds: the merge is not concluded:"
    cat "${call}/problems.txt"
    if [ "${attempt}" -eq 2 ]; then
      echo 0 > "${dir}/rounds"
      {
        echo "Claude could not conclude the conflicted merge in two attempts. What was left:"
        echo
        sed 's/^/- /' "${call}/problems.txt"
      } > "${dir}/report.md"
      finish unresolved-conflict
    fi
  done
  merge="$(git rev-list --parents "${pre_merge}..HEAD" | awk -v a="${pre_merge}" -v b="${MERGE_HEAD}" '$2 == a && $3 == b { print $1 }')"
  {
    echo "The merge conflicted in $(paste -sd, "${work}/conflicts.txt" | sed 's/,/, /g'); Claude resolved it in CI as merge commit ${merge:0:10}. Check that every conflict kept both sides"
    "${scripts}/review-checks.sh" "${BASE}" HEAD
  } > "${reasons}"
  echo "claude-rounds: merge concluded as ${merge}; reasons now:"
  cat "${reasons}"
  "${scripts}/upstream-draft.sh" manual "${work}"
fi

# 1-3. The rounds.
start="$(git rev-parse HEAD)"
rm -f "${verdict}"
result=failed
for round in $(seq 1 "${max}"); do
  call="${dir}/round-${round}"
  mkdir -p "${call}"
  {
    cat "${dir}/trusted/prompt.md"
    context resolve
    if [ "${round}" -eq 1 ] && [ -s "${work}/initial-failure.txt" ]; then
      echo
      echo "### The merged tree already failed before you were called"
      echo
      echo "The workflow built and started the merge as it was, and this is what failed. Fixing it is"
      echo "part of the reasons above."
      echo
      echo '```'
      cat "${work}/initial-failure.txt"
      echo '```'
    elif [ "${round}" -gt 1 ]; then
      echo
      echo "### Round ${round}: what you committed in round $((round - 1)) did not pass"
      echo
      echo "The workflow built and checked your commits, and only this failed. Fix only that,"
      echo "commit, and update the verdict if what you did for a reason changed."
      echo
      echo '```'
      cat "${dir}/round-$((round - 1))/verify/failure.txt"
      echo '```'
    fi
  } > "${call}/prompt.md"

  run_claude "${call}/prompt.md" "${call}/claude.log"
  echo "${round}" > "${dir}/rounds"
  if "${scripts}/verify-resolution.sh" --out "${call}/verify" --base "${BASE}" --contains "${start}" \
      --reasons "${reasons}" --verdict "${verdict}" --notes "${notes}"; then
    result=passed
    break
  fi
  if [ -f "${verdict}" ] && python3 -I -c 'import json, sys
v = json.load(open(sys.argv[1]))
sys.exit(0 if any(isinstance(r, dict) and r.get("status") == "needs-human" for r in v.get("reasons", [])) else 1)' "${verdict}" 2>/dev/null; then
    result=needs-human
    break
  fi
done

# The report, for the issue: Claude's verdict, then what each round ran into.
{
  if [ -f "${verdict}" ] && python3 -I - "${verdict}" <<'PY'
import json, sys
try:
    v = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    sys.exit(1)
print(str(v.get("summary", "")).strip() or "(no summary)")
print()
for n, r in enumerate(v.get("reasons", []), 1):
    if isinstance(r, dict):
        print(f"{n}. **{r.get('status', '?')}**: {r.get('reason', '')}")
        if r.get("detail"):
            print(f"   {str(r['detail']).strip()}")
PY
  then
    :
  else
    echo "Claude wrote no readable verdict."
  fi
  for round in $(seq 1 "$(cat "${dir}/rounds")"); do
    failure="${dir}/round-${round}/verify/failure.txt"
    echo
    if [ -s "${failure}" ]; then
      echo "<details><summary>Round ${round}: did not pass</summary>"
      echo
      echo '```'
      # An issue holds 65536 characters; three full rounds could come close.
      head -n 80 "${failure}"
      echo '```'
      echo
      echo "</details>"
    else
      echo "Round ${round}: passed: built, tests green, the jar started, every check satisfied."
    fi
  done
} > "${dir}/report.md"

finish "${result}"
