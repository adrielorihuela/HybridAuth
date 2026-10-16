#!/usr/bin/env python3
"""Fills in the release-note templates in docs/releases/.

  render-notes.py release  --version V --previous vX.Y.Z --repo owner/name [--bump-reason TEXT]
  render-notes.py upstream --mode auto|manual --version V --previous vX.Y.Z --base SHA
                           --velocity-ref REF [--reasons FILE] [--resolved FILE]

`release` is for a release published because a change reached main (docs/releases/TEMPLATE.md).
`upstream` is for the upstream update (docs/releases/TEMPLATE-upstream.md); in manual mode it
drafts the notes with MANUAL sections for whoever resolves the update to fill in.

The rendered notes go to standard output. Test counts are read from the build's JUnit reports, so
render after `./gradlew build`. The repository is the one this script is in, unless REPO_ROOT
names another: upstream.yml runs a copy taken before Claude could edit anything.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(os.environ.get("REPO_ROOT") or Path(__file__).resolve().parents[2])
VELOCITY_REPO = "PaperMC/Velocity"

# Conventional-commit types, and the heading each one is listed under.
GROUPS = [
    ("New", {"feat"}),
    ("Fixes", {"fix"}),
    ("Code", {"refactor", "perf", "style", "test"}),
    ("Documentation", {"docs"}),
    ("Build and CI", {"build", "ci", "chore", "deps"}),
]
SUBJECT = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?(?P<bang>!)?:\s*(?P<rest>.+)$")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True,
                          text=True).stdout


def commits(rev_range: str, *paths: str) -> list[tuple[str, str]]:
    """Non-merge commits in the range, oldest first, as (sha, subject)."""
    out = git("log", "--no-merges", "--reverse", "--format=%H%x1f%s", rev_range, "--", *paths)
    return [tuple(line.split("\x1f", 1)) for line in out.splitlines() if line]


def test_count() -> str:
    total = failures = 0
    for report in glob.glob(str(ROOT / "**/build/test-results/test/*.xml"), recursive=True):
        suite = ET.parse(report).getroot()
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    if total == 0:
        return "its"
    return f"{total}" if failures == 0 else f"{total} ({failures} failing)"


def fill(template: str, values: dict[str, str]) -> str:
    text = (ROOT / "docs/releases" / template).read_text(encoding="utf-8")
    text = re.sub(r"\A<!--.*?-->\n", "", text, flags=re.S)  # the template's own explanation
    for key, value in values.items():
        text = text.replace("{{" + key + "}}", value)
    missing = re.findall(r"\{\{(\w+)\}\}", text)
    if missing:
        sys.exit(f"render-notes: no value for {', '.join(sorted(set(missing)))}")
    return re.sub(r"\n{3,}", "\n\n", text).strip() + "\n"


def link(repo: str, sha: str) -> str:
    return f"[`{sha[:7]}`](https://github.com/{repo}/commit/{sha})"


def release(args: argparse.Namespace) -> str:
    rev_range = f"{args.previous}..HEAD" if args.previous else "HEAD"
    # Commits an upstream merge brought in are Velocity's, not the fork's, and are listed apart.
    from_upstream: set[str] = set()
    merges = git("log", "--merges", "--format=%H%x1f%s", rev_range).splitlines()
    for sha, subject in (line.split("\x1f", 1) for line in merges if line):
        if re.search(r"upstream", subject, re.I):
            from_upstream |= {c for c, _ in commits(f"{sha}^1..{sha}^2")}
    grouped: dict[str, list[str]] = {name: [] for name, _ in GROUPS}
    grouped["Other"] = []
    grouped["From upstream Velocity"] = []
    total = 0
    for sha, subject in commits(rev_range):
        if subject.startswith("chore: release "):
            continue
        total += 1
        if sha in from_upstream:
            grouped["From upstream Velocity"].append(f"- {subject} ({link(VELOCITY_REPO, sha)})")
            continue
        match = SUBJECT.match(subject)
        kind = match.group("type") if match else ""
        text = match.group("rest") if match else subject
        text = text[0].upper() + text[1:]
        if match and match.group("bang"):
            text = "**Breaking:** " + text
        heading = next((name for name, kinds in GROUPS if kind in kinds), "Other")
        grouped[heading].append(f"- {text} ({link(args.repo, sha)})")

    sections = [f"## {name}\n\n" + "\n".join(items) for name, items in grouped.items() if items]
    head = git("rev-parse", "HEAD").strip()
    previous = args.previous or "the first release"
    return fill("TEMPLATE.md", {
        "intro": f"**HybridAuth {args.version}**, with the {total} commit(s) that reached `main` "
                 f"since {previous}.",
        "changes": "\n\n".join(sections) or "No changes to the jar.",
        "compare": f"https://github.com/{args.repo}/compare/{previous}...v{args.version}",
        "trigger": f"The push of {link(args.repo, head)}",
        "previous": previous,
        "bump_reason": args.bump_reason or "see the changes above",
        "tests": test_count(),
        "upgrade_note": "",
    })


def lines(path: str | None) -> list[str]:
    if not path or not Path(path).is_file():
        return []
    return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines()
            if line.strip()]


def upstream(args: argparse.Namespace) -> str:
    velocity = [c for c in commits(f"{args.base}..{args.velocity_ref}")]
    properties = git("show", f"{args.velocity_ref}:gradle.properties")
    velocity_version = re.search(r"^version=(.+)$", properties, re.M).group(1).strip()

    limbo_squash = [sha for sha, subject in commits(f"{args.base}..HEAD", "limbo/upstream")
                    if subject.startswith("Squashed 'limbo/upstream/'")]
    limbo_lines = []
    for sha in limbo_squash:
        body = git("log", "-1", "--format=%b", sha)
        limbo_lines += [f"- {line.strip()}" for line in body.splitlines()
                        if line.strip() and not line.startswith("git-subtree")]

    resolved = lines(args.resolved)
    reasons = lines(args.reasons)
    manual = args.mode == "manual"

    if velocity:
        velocity_section = (f"{len(velocity)} commit(s) from [{VELOCITY_REPO}]"
                            f"(https://github.com/{VELOCITY_REPO}):\n\n"
                            + "\n".join(f"- {subject} ({link(VELOCITY_REPO, sha)})"
                                        for sha, subject in velocity))
    else:
        velocity_section = "No changes from Velocity."
    limbo_section = ("From [Nan1t/NanoLimbo](https://github.com/Nan1t/NanoLimbo), the authentication "
                     "server:\n\n" + "\n".join(limbo_lines)) if limbo_lines else ""

    resolved_text = ""
    if resolved:
        resolved_text = ("Conflicts in files both projects edit were resolved mechanically, keeping "
                         "upstream's values and the fork's own entries: "
                         + ", ".join(f"`{f}`" for f in resolved) + ".")

    checks = [
        "the merge, done the documented way: merged, never rebased, without upstream's tags",
        f"`./gradlew build`: compilation, Checkstyle, Spotless and {test_count()} tests",
        "a smoke test: the jar started, the authentication server came up, the proxy reached Done",
        "the review checks — config-version, new protocol versions, the event API the "
        "authentication gate uses, new getAllServers() callers, workflow changes, the respawn "
        "registration portals rely on, and the NanoLimbo patches — and none fired",
    ]
    if manual:
        how = ("**Not automatically.** The upstream update stopped because something needed "
               "a person or Claude to look at it:\n\n"
               + "\n".join(f"- {reason}" for reason in reasons)
               + "\n\n### What was done\n\n<!-- MANUAL: what was found for each point above, what "
               "was changed and why, and how it was verified. Then delete this comment. -->")
        fork = (resolved_text + "\n\n" if resolved_text else "") + (
            "<!-- MANUAL: anything the fork had to change for this update, or \"Nothing.\" -->")
        intro_tail = "<!-- MANUAL: one or two sentences on what matters in this update. -->"
    else:
        how = ("**Automatically.** The upstream update merged this, and everything that "
               "would have needed a person passed, so it was merged into `main` and released "
               "without anyone:\n\n" + "\n".join(f"- {check}" for check in checks)
               + "\n\nThese notes were generated from `docs/releases/TEMPLATE-upstream.md`.")
        fork = "Nothing. " + resolved_text if resolved_text else "Nothing."
        intro_tail = "Nothing this fork adds has changed, so upgrading is replacing the jar."

    return fill("TEMPLATE-upstream.md", {
        "velocity_version": velocity_version,
        "limbo_clause": " and the latest NanoLimbo" if limbo_lines else "",
        "intro_tail": intro_tail,
        "velocity_section": velocity_section,
        "limbo_section": limbo_section,
        "fork_section": fork,
        "how": how,
        "previous": args.previous,
    })


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="kind", required=True)
    rel = sub.add_parser("release")
    rel.add_argument("--version", required=True)
    rel.add_argument("--previous", default="")
    rel.add_argument("--repo", required=True)
    rel.add_argument("--bump-reason", default="")
    up = sub.add_parser("upstream")
    up.add_argument("--mode", choices=["auto", "manual"], required=True)
    up.add_argument("--version", required=True)
    up.add_argument("--previous", required=True)
    up.add_argument("--base", required=True)
    up.add_argument("--velocity-ref", required=True)
    up.add_argument("--reasons")
    up.add_argument("--resolved")
    args = parser.parse_args()
    sys.stdout.write(release(args) if args.kind == "release" else upstream(args))


if __name__ == "__main__":
    main()
