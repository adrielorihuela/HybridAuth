#!/usr/bin/env python3
"""Cuts a failed build, smoke test or verification down to the part that says what is wrong.

  extract-failure.py [--checks FILE] [--build FILE] [--smoke FILE] [--root DIR] [--since EPOCH]
                     [--max-lines N]

Claude resolves upstream updates in CI without building (upstream.yml builds for it, which costs
GitHub's CPU rather than Claude's tokens), so when a round fails, what it gets back is this: a few
dozen lines naming the file, line and test, instead of thousands of lines of Gradle log. Pass only
the logs of the stages that failed:

  --checks  verify-resolution.sh's own checks (MANUAL left, conflict markers, the verdict, ...),
            included whole: they are written to be short.
  --build   the output of ./gradlew --console=plain build. From it: the task that failed, Java
            (`File.java:12: error:`) and Kotlin (`e: File.kt:12:5`) compiler errors with their
            source line, Checkstyle and Spotless violations, and failing tests with their message
            and the first lines of their stack trace, read from build/test-results under --root
            (only reports written after --since, if given, so an earlier round's do not count). When
            none of those is there, the end of the log.
  --smoke   the output of smoke-test.sh: what was missing, the proxy's errors and the end of its log.

Paths are shown relative to --root (default: the current directory). The result is capped at
--max-lines (default 150) and goes to standard output.
"""

import argparse
import glob
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

JAVAC = re.compile(r"^(?P<path>\S+\.java):(?P<line>\d+): error: (?P<msg>.*)$")
KOTLIN = re.compile(r"^e: (?:file://)?(?P<path>\S+?\.kts?):(?P<line>\d+):(?P<col>\d+) (?P<msg>.*)$")
CHECKSTYLE = re.compile(r"\[ant:checkstyle\] \[(?:ERROR|WARN)\] (?P<msg>.*)$")
CONSOLE_TEST = re.compile(r"^\S.* > .* FAILED$")
TASK_FAILED = re.compile(r"^> Task (\S+) FAILED$")


def relative(path: str, root: Path) -> str:
    try:
        return str(Path(path).resolve().relative_to(root))
    except ValueError:
        return path


def what_went_wrong(lines: list[str], root: Path) -> list[str]:
    """Gradle's own summary: the failed task and its one-line reason, per failure."""
    out: list[str] = []
    for i, line in enumerate(lines):
        if line.strip() == "* What went wrong:":
            block = []
            for follow in lines[i + 1:i + 12]:
                if follow.startswith("* Try:") or follow.startswith("* Exception is:"):
                    break
                if follow.strip():
                    block.append(follow.rstrip().replace(str(root) + "/", ""))
            out += block[:8]
    return out


def compile_errors(lines: list[str], root: Path) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for i, line in enumerate(lines):
        match = JAVAC.match(line) or KOTLIN.match(line)
        if not match:
            continue
        key = f"{relative(match.group('path'), root)}:{match.group('line')}"
        entry = f"{key}: {match.group('msg').strip()}"
        if entry in seen:
            continue
        seen.add(entry)
        out.append(entry)
        if JAVAC.match(line):
            # javac prints the offending source line and a caret under it.
            for follow in lines[i + 1:i + 3]:
                if JAVAC.match(follow) or not follow.strip() or follow.startswith(("> ", "* ")):
                    break
                out.append("    " + follow.rstrip())
    return out


def checkstyle(lines: list[str], root: Path) -> list[str]:
    out = []
    for line in lines:
        match = CHECKSTYLE.search(line)
        if match:
            msg = match.group("msg")
            path = msg.split(":", 1)[0]
            out.append(msg.replace(path, relative(path, root), 1) if path.startswith("/") else msg)
    return sorted(set(out), key=out.index)


def spotless(lines: list[str], root: Path) -> list[str]:
    out: list[str] = []
    for i, line in enumerate(lines):
        if "The following files had format violations:" in line:
            for follow in lines[i:i + 40]:
                out.append(follow.rstrip().replace(str(root) + "/", ""))
                if "spotlessApply" in follow:
                    break
    return out


def failing_tests(root: Path, since: float | None, lines: list[str]) -> list[str]:
    out: list[str] = []
    reports = glob.glob(str(root / "**/build/test-results/**/*.xml"), recursive=True)
    for report in sorted(reports):
        if since is not None and os.path.getmtime(report) < since:
            continue
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                message = (problem.get("message") or "").strip().replace("\n", " ")
                if len(message) > 300:
                    message = message[:300] + "..."
                out.append(f"{case.get('classname')} > {case.get('name')}: {message}")
                stack = [l.rstrip() for l in (problem.text or "").splitlines() if l.strip()]
                # The exception line itself repeats the message; the frames say where.
                out += ["    " + l.strip() for l in stack[1:7]]
    if not out:
        # No reports (or none fresh): fall back to what Gradle printed about them.
        for i, line in enumerate(lines):
            if CONSOLE_TEST.match(line):
                out.append(line.rstrip())
                out += [l.rstrip() for l in lines[i + 1:i + 3] if l.startswith("    ")]
    return out


def build_section(path: Path, root: Path, since: float | None) -> list[str]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    out = ["## ./gradlew build failed"]
    tasks = [m.group(1) for m in map(TASK_FAILED.match, lines) if m]
    if tasks:
        out.append("Failed task(s): " + ", ".join(dict.fromkeys(tasks)))
    found = False
    for title, items in (("Compilation errors", compile_errors(lines, root)),
                         ("Checkstyle", checkstyle(lines, root)),
                         ("Spotless (formatting)", spotless(lines, root)),
                         ("Failing tests", failing_tests(root, since, lines)
                          if any(":test" in t for t in tasks) or not tasks else [])):
        if items:
            found = True
            out += ["", f"### {title}", *items]
    summary = what_went_wrong(lines, root)
    if summary:
        out += ["", "### What Gradle reported", *summary]
    if not found:
        out += ["", "### End of the log", *[l.rstrip() for l in lines[-60:]]]
    return out


def smoke_section(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    out = ["## The smoke test failed (the jar was started; this is what it did)"]
    out += [l for l in lines if l.startswith(("ok:", "missing:"))]
    errors = [l.rstrip() for l in lines
              if re.search(r"\b(ERROR|Exception|Error:|Caused by)", l)][:25]
    if errors:
        out += ["", "### Errors in the proxy log", *errors]
    log_start = next((i for i, l in enumerate(lines) if l.startswith("---- proxy log")), None)
    tail = lines[log_start + 1:] if log_start is not None else lines
    out += ["", "### End of the proxy log", *[l.rstrip() for l in tail[-40:]]]
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checks")
    parser.add_argument("--build")
    parser.add_argument("--smoke")
    parser.add_argument("--root", default=".")
    parser.add_argument("--since", type=float)
    parser.add_argument("--max-lines", type=int, default=150)
    args = parser.parse_args()
    root = Path(args.root).resolve()

    out: list[str] = []
    if args.checks and Path(args.checks).is_file():
        checks = Path(args.checks).read_text(encoding="utf-8").strip().splitlines()
        if checks:
            out += ["## The verification checks failed", *checks, ""]
    if args.build and Path(args.build).is_file():
        out += build_section(Path(args.build), root, args.since) + [""]
    if args.smoke and Path(args.smoke).is_file():
        out += smoke_section(Path(args.smoke)) + [""]

    while out and not out[-1].strip():
        out.pop()
    if len(out) > args.max_lines:
        cut = len(out) - args.max_lines + 1
        out = out[:args.max_lines - 1] + [f"[... {cut} more line(s) cut]"]
    print("\n".join(out))


if __name__ == "__main__":
    main()
