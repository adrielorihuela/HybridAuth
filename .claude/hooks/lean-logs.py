#!/usr/bin/env python3
"""LeanFlow PostToolUse hook for Bash: keeps long command output out of the main context.

When a Bash result is longer than LEAN_LOG_MAX_LINES lines (default 80) or LEAN_LOG_MAX_CHARS
characters (default 6000), the full output is written to .claude/logs/<time>-<command>.log and
Claude sees only: the exit status, the log path, and the last LEAN_LOG_TAIL lines (default 25).
Claude greps the log for details (deterministic digest, per Anthropic's costs doc); a `log-reader` subagent is only for very large logs.

The command itself is untouched and goes through the normal permission flow: this hook runs
after the tool. If anything goes wrong it prints nothing and exits 0, so the original output
reaches Claude unchanged. LEAN_LOG_FORMAT selects how the replacement is returned
("string" or "object"); lean-code's install self-test records the one this Claude Code accepts.
"""
import datetime as dt
import json
import os
import re
import sys


def main():
    try:
        data = json.load(sys.stdin)
        if data.get("tool_name") != "Bash":
            return
        resp = data.get("tool_response") or {}
        if isinstance(resp, str):
            out, err = resp, ""
        else:
            out, err = resp.get("stdout") or "", resp.get("stderr") or ""
        text = out + (("\n[stderr]\n" + err) if err else "")
        max_lines = int(os.environ.get("LEAN_LOG_MAX_LINES", "80"))
        max_chars = int(os.environ.get("LEAN_LOG_MAX_CHARS", "6000"))
        lines = text.splitlines()
        if len(lines) <= max_lines and len(text) <= max_chars:
            return
        root = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        logdir = os.path.join(root, ".claude", "logs")
        os.makedirs(logdir, exist_ok=True)
        cmd = (data.get("tool_input") or {}).get("command", "cmd")
        slug = re.sub(r"[^A-Za-z0-9]+", "-", cmd)[:40].strip("-") or "cmd"
        path = os.path.join(logdir, f"{dt.datetime.now():%Y%m%d-%H%M%S}-{slug}.log")
        with open(path, "w") as f:
            f.write(f"$ {cmd}\n{text}\n")
        tail_n = int(os.environ.get("LEAN_LOG_TAIL", "25"))
        rel = os.path.relpath(path, root)
        pat = re.compile(r"(error|fail|failed|exception|traceback|panic|fatal|warn)", re.I)
        tail_set = set(range(max(0, len(lines) - tail_n), len(lines)))
        errs = [f"{i + 1}: {l}" for i, l in enumerate(lines) if pat.search(l) and i not in tail_set][:20]
        summary = (f"[LeanFlow] Output was {len(lines)} lines; full log saved to {rel} (grep it for details)."
                   + (f"\nError-like lines ({len(errs)} shown):\n" + "\n".join(errs) if errs else "")
                   + f"\nLast {tail_n} lines:\n" + "\n".join(lines[-tail_n:]))
        if os.environ.get("LEAN_LOG_FORMAT", "object") == "string":
            replacement = summary
        else:
            replacement = dict(resp) if isinstance(resp, dict) else {}
            replacement.update({"stdout": summary, "stderr": ""})
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "updatedToolOutput": replacement}}))
    except Exception:
        return


if __name__ == "__main__":
    main()
