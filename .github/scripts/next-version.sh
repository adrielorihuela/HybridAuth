#!/usr/bin/env bash
# Decides what, if anything, release.yml should publish for the commit checked out.
#
# Prints key=value lines (meant for $GITHUB_OUTPUT):
#   mode=none|manual|auto   none: nothing to release. manual: gradle.properties already names an
#                           unreleased version, so publish exactly that. auto: work the version out
#                           from the commits and write it into gradle.properties first.
#   version=X.Y.Z           the version to publish (absent for none)
#   previous=vX.Y.Z         the latest stable tag, which the notes compare against
#   bump=major|minor|patch  for auto, and why (bump_reason=...)
#   reason=...              one line for the log
#
# Usage: next-version.sh <ref-type> <ref-name>   (GITHUB_REF_TYPE and GITHUB_REF_NAME)
#
# A commit counts only if it changes something other than documentation, CI or the repository's own
# git settings (.gitignore, .gitattributes): those leave the jar
# identical, and a release whose jar is the same as the last one only adds noise. The release
# commits this workflow makes itself are skipped too.
set -euo pipefail

ref_type="${1:?ref type}"
ref_name="${2:?ref name}"

out() { printf '%s\n' "$1"; }

props_version="$(grep '^forkVersion=' gradle.properties | cut -d= -f2)"
previous="$(git tag --list 'v[0-9]*' --sort=-v:refname | grep -v -- '-' | head -n 1 || true)"
out "previous=${previous}"

tag_exists() { git rev-parse -q --verify "refs/tags/v$1" > /dev/null; }

# Whether version $1 is strictly greater than version $2 (plain X.Y.Z, or X.Y.Z-suffix).
greater() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$1" ]; }

if [ "${ref_type}" = "tag" ]; then
  if [ "v${props_version}" != "${ref_name}" ]; then
    echo "::error::Tag ${ref_name} does not match forkVersion=${props_version} in gradle.properties." >&2
    exit 1
  fi
  out "mode=manual"; out "version=${props_version}"; out "reason=tag ${ref_name} pushed"
  exit 0
fi

# Pre-releases: a hyphenated forkVersion with no tag yet, from any branch.
if [[ "${props_version}" == *-* ]]; then
  if tag_exists "${props_version}"; then
    out "mode=none"; out "reason=v${props_version} already exists"
  else
    out "mode=manual"; out "version=${props_version}"; out "reason=pre-release ${props_version}"
  fi
  exit 0
fi

if [ "${ref_name}" != "main" ]; then
  out "mode=none"; out "reason=stable versions are released from main, not ${ref_name}"
  exit 0
fi

# Somebody bumped forkVersion by hand, usually with notes in docs/releases/<version>.md.
if ! tag_exists "${props_version}" && { [ -z "${previous}" ] || greater "${props_version}" "${previous#v}"; }; then
  out "mode=manual"; out "version=${props_version}"; out "reason=forkVersion ${props_version} set by hand"
  exit 0
fi

relevant() {
  # A merge commit's own diff is empty here; the commits it brings in are listed on their own.
  git diff-tree --no-commit-id --name-only -r "$1" \
    | grep -Ev '^(docs/|\.github/|\.git(ignore|attributes)$)|\.md$' | grep -q .
}

bump=""
reason=""
range="${previous:+${previous}..}HEAD"
while IFS= read -r sha; do
  subject="$(git log -1 --format=%s "${sha}")"
  case "${subject}" in "chore: release "*) continue ;; esac
  relevant "${sha}" || continue
  message="$(git log -1 --format=%B "${sha}")"
  if [[ "${subject}" =~ ^[a-z]+(\([^\)]*\))?!: ]] || grep -q '^BREAKING CHANGE' <<< "${message}"; then
    bump="major"; reason="${subject}"; break
  elif [[ "${subject}" =~ ^feat(\([^\)]*\))?: ]]; then
    if [ "${bump}" != "minor" ]; then bump="minor"; reason="${subject}"; fi
  elif [ -z "${bump}" ]; then
    bump="patch"; reason="${subject}"
  fi
done < <(git rev-list --no-merges "${range}")

if [ -z "${bump}" ]; then
  out "mode=none"; out "reason=no commits since ${previous:-the start} change the jar"
  exit 0
fi

IFS=. read -r major minor patch <<< "${previous#v}"
case "${bump}" in
  major) version="$((major + 1)).0.0" ;;
  minor) version="${major}.$((minor + 1)).0" ;;
  patch) version="${major}.${minor}.$((patch + 1))" ;;
esac
out "mode=auto"; out "version=${version}"; out "bump=${bump}"
out "bump_reason=${reason}"; out "reason=${bump} bump to ${version}, because of: ${reason}"
