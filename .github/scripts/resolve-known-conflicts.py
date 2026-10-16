#!/usr/bin/env python3
"""Resolves the one merge conflict an upstream update produces almost every time, and nothing else.

  resolve-known-conflicts.py FILE...

Every Velocity merge so far has conflicted in gradle.properties or gradle/libs.versions.toml, and
always the same way: upstream bumps a value on a line next to one the fork added (forkVersion,
sqlite-jdbc). Those files are flat `key = value` lists, so the conflict can be resolved key by key
with certainty, which is what this does. Anything else is left for a person.

The merge must have been made with merge.conflictStyle=diff3, so each hunk carries the common
ancestor and every key can be resolved three ways:

  - changed on one side only        -> that side's line
  - changed the same way on both    -> that line
  - changed differently on both     -> refuse
  - added by one side               -> kept (the fork's own entries, upstream's new ones)
  - deleted by one side, untouched on the other -> deleted
  - deleted by one side, changed on the other   -> refuse

A hunk holding anything but blank lines, `#` comments and `key = value` lines is refused, and so is
a comment only upstream has, since there is nowhere certain to put it. Exit status 0 means every
file was resolved and rewritten; 1 means none was touched.
"""

import re
import sys
from pathlib import Path

ALLOWED = {"gradle.properties", "gradle/libs.versions.toml"}
KEY = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*=")


class Refused(Exception):
    pass


def kind(line: str) -> str:
    stripped = line.strip()
    if not stripped:
        return "blank"
    if stripped.startswith("#"):
        return "comment"
    if KEY.match(line):
        return "key"
    raise Refused(f"not a key = value line: {line.rstrip()}")


def keyed(lines: list[str]) -> dict[str, str]:
    out = {}
    for line in lines:
        if kind(line) == "key":
            out[KEY.match(line).group(1)] = line
    return out


def resolve_hunk(ours: list[str], base: list[str], theirs: list[str]) -> list[str]:
    o, b, t = keyed(ours), keyed(base), keyed(theirs)
    norm = lambda line: line.strip() if line is not None else None  # noqa: E731

    their_comments = {line.strip() for line in theirs if kind(line) == "comment"}
    our_comments = {line.strip() for line in ours if kind(line) == "comment"}
    base_comments = {line.strip() for line in base if kind(line) == "comment"}
    if their_comments - our_comments - base_comments:
        raise Refused("upstream added a comment inside the conflict")

    def pick(key: str) -> str | None:
        mine, old, new = o.get(key), b.get(key), t.get(key)
        if norm(mine) == norm(new):
            return mine
        if norm(mine) == norm(old):
            return new  # only upstream changed it, or deleted it (None)
        if norm(new) == norm(old):
            return mine  # only the fork changed it, or deleted it (None)
        raise Refused(f"{key} changed differently on both sides")

    result = []
    for line in ours:
        if kind(line) != "key":
            result.append(line)
            continue
        chosen = pick(KEY.match(line).group(1))
        if chosen is not None:
            result.append(chosen)
    for key, line in t.items():
        if key not in o:
            chosen = pick(key)
            if chosen is not None:
                result.append(chosen)
    return result


def resolve_file(path: Path) -> str:
    out: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    i = 0
    while i < len(lines):
        if not lines[i].startswith("<<<<<<<"):
            out.append(lines[i])
            i += 1
            continue
        sections = {"ours": [], "base": None, "theirs": []}
        current = "ours"
        i += 1
        while i < len(lines) and not lines[i].startswith(">>>>>>>"):
            if lines[i].startswith("|||||||"):
                sections["base"], current = [], "base"
            elif lines[i].startswith("======="):
                current = "theirs"
            else:
                sections[current].append(lines[i])
            i += 1
        if i == len(lines):
            raise Refused("unterminated conflict")
        if sections["base"] is None:
            raise Refused("the merge was not made with merge.conflictStyle=diff3")
        out += resolve_hunk(sections["ours"], sections["base"], sections["theirs"])
        i += 1
    return "".join(out)


def main() -> int:
    files = sys.argv[1:]
    if not files:
        print(__doc__)
        return 1
    resolved = {}
    for name in files:
        if name not in ALLOWED:
            print(f"refused: {name} is not a file this script knows how to resolve")
            return 1
        try:
            resolved[name] = resolve_file(Path(name))
        except Refused as reason:
            print(f"refused: {name}: {reason}")
            return 1
    for name, text in resolved.items():
        Path(name).write_text(text, encoding="utf-8")
        print(f"resolved: {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
