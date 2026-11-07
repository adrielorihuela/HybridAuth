#!/usr/bin/env python3
"""LeanFlow weekly maintenance for one project. Runs without an LLM, so it costs no tokens.

Usage (from the project root):
    python3 .claude/lean/lean_maint.py weekly            # skip if checked recently
    python3 .claude/lean/lean_maint.py weekly --force    # run now
    python3 .claude/lean/lean_maint.py clean | version | telemetry

What `weekly` does, in order, printing one line per step:
  1. SKIP if every preset in .claude/lean-version.json was checked less than check_every_days ago.
  2. CLEAN  old LeanFlow by-products (disk hygiene only; no token effect): *.bak older than 14 days, .claude/logs older than 7 days
            (and the folder capped at 50 MB), a stale .claude/workflow-setup-plan.md.
  3. VERSION each preset against the LeanFlow plugin installed on this machine:
            CURRENT / BEHIND <old> -> <new> / UNKNOWN (plugin copy not found).
  4. SIZE   warns when CLAUDE.md is over 200 lines.
  5. TELEMETRY  token use by model since the last report, read from Claude Code's local
            transcripts (~/.claude/projects/<project>/), appended to the LeanFlow repo under
            telemetry/<host>/<project>.jsonl and pushed. Pulls telemetry/advice/<project>.md
            into .claude/lean-advice.md if the weekly loop left advice. Never fatal.
  6. Updates last_check. Final line: ACTION NONE, or ACTION RUN <skill> (<reason>).

The CLAUDE.md rule reads only the final line: ACTION NONE means nothing else to do.
"""
import datetime as dt
import glob
import json
import os
import re
import shutil
import socket
import subprocess
import sys

ROOT = os.getcwd()
VFILE = os.path.join(ROOT, ".claude", "lean-version.json")
LEAN_DIR = os.path.join(ROOT, ".claude", "lean")
REPO_URL = os.environ.get("LEANFLOW_REPO_URL", "https://github.com/adrielorihuela/LeanFlow.git")
CACHE = os.path.expanduser(os.environ.get("LEANFLOW_CACHE", "~/.cache/leanflow"))
# Output prices per MTok, input, cache read; used only for a rough cost estimate in telemetry.
PRICES = {
    "haiku": (0.10, 0.50, 0.01), "sonnet": (2.0, 10.0, 0.10),
    "opus": (4.0, 20.0, 0.20), "fable": (10.0, 50.0, 0.25), "mythos": (10.0, 50.0, 0.25),
}
TODAY = dt.date.today()


def load_versions():
    if not os.path.exists(VFILE):
        return {"presets": {}}
    d = json.load(open(VFILE))
    if "presets" not in d:  # migrate old single-object shape
        d = {"presets": {d.get("preset", "lean-code"): d}}
    return d


def save_versions(d):
    os.makedirs(os.path.dirname(VFILE), exist_ok=True)
    json.dump(d, open(VFILE, "w"), indent=2)
    open(VFILE, "a").write("\n")


def days_since(s):
    try:
        return (TODAY - dt.date.fromisoformat(s)).days
    except Exception:
        return 10**6


def step_clean():
    removed, freed = 0, 0
    now = dt.datetime.now().timestamp()
    cands = glob.glob(os.path.join(ROOT, "*.bak")) + glob.glob(os.path.join(ROOT, ".claude", "**", "*.bak"), recursive=True)
    for f in cands:
        if now - os.path.getmtime(f) > 14 * 86400:
            freed += os.path.getsize(f); os.remove(f); removed += 1
    logs = os.path.join(ROOT, ".claude", "logs")
    if os.path.isdir(logs):
        files = sorted(glob.glob(os.path.join(logs, "*")), key=os.path.getmtime)
        for f in files:
            if now - os.path.getmtime(f) > 7 * 86400:
                freed += os.path.getsize(f); os.remove(f); removed += 1
        files = sorted(glob.glob(os.path.join(logs, "*")), key=os.path.getmtime)
        total = sum(os.path.getsize(f) for f in files)
        while files and total > 50 * 1024 * 1024:
            f = files.pop(0); s = os.path.getsize(f); total -= s; freed += s; os.remove(f); removed += 1
    plan = os.path.join(ROOT, ".claude", "workflow-setup-plan.md")
    if os.path.exists(plan) and now - os.path.getmtime(plan) > 7 * 86400:
        os.remove(plan); removed += 1
    print(f"CLEAN removed={removed} freed_kb={freed // 1024}")


def plugin_version(preset):
    pats = [
        os.path.expanduser(f"~/.claude/plugins/**/{preset}/assets/lean-version.json"),
        os.path.expanduser(f"~/.claude/**/leanflow/**/{preset}/assets/lean-version.json"),
    ]
    best = None
    for p in pats:
        for f in glob.glob(p, recursive=True):
            try:
                v = json.load(open(f)).get("version")
                if v and (best is None or v > best):
                    best = v
            except Exception:
                pass
    return best


def step_version(d):
    behind = []
    for name, entry in d["presets"].items():
        pv = plugin_version(name)
        if pv is None:
            print(f"VERSION {name} UNKNOWN (plugin copy not found on this machine)")
        elif pv > entry.get("version", ""):
            print(f"VERSION {name} BEHIND {entry.get('version')} -> {pv}")
            behind.append(name)
        else:
            print(f"VERSION {name} CURRENT {entry.get('version')}")
    return behind


def step_size():
    p = os.path.join(ROOT, "CLAUDE.md")
    n = sum(1 for _ in open(p)) if os.path.exists(p) else 0
    print(f"SIZE CLAUDE.md lines={n}" + (" OVER_200" if n > 200 else ""))
    return n > 200


def project_slug():
    return re.sub(r"[^A-Za-z0-9]", "-", ROOT)


def family(model):
    m = (model or "").lower()
    for k in PRICES:
        if k in m:
            return k
    return "other"


def collect_usage(since_iso):
    base = os.path.expanduser(f"~/.claude/projects/{project_slug()}")
    agg, sessions, main_out, side_out = {}, set(), 0, 0
    seen = {}  # message id -> usage; parallel tool calls repeat the same id (count once, keep the largest)
    if not os.path.isdir(base):
        return None
    for f in glob.glob(os.path.join(base, "**", "*.jsonl"), recursive=True):
        is_sub = "subagent" in f
        try:
            for line in open(f, errors="ignore"):
                if '"usage"' not in line:
                    continue
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                ts = r.get("timestamp", "")
                if since_iso and ts and ts < since_iso:
                    continue
                msg = r.get("message") or {}
                u = msg.get("usage") or {}
                if not u:
                    continue
                mid = msg.get("id") or r.get("uuid") or f"{f}:{ts}"
                prev = seen.get(mid)
                if prev and (prev[1].get("output_tokens", 0) or 0) >= (u.get("output_tokens", 0) or 0):
                    continue
                seen[mid] = (family(msg.get("model")), u, is_sub or bool(r.get("isSidechain")))
                if r.get("sessionId"):
                    sessions.add(r["sessionId"])
        except Exception:
            continue
    for fam, u, side in seen.values():
        a = agg.setdefault(fam, {"in": 0, "out": 0, "cache_read": 0, "cache_write": 0})
        a["in"] += u.get("input_tokens", 0) or 0
        a["out"] += u.get("output_tokens", 0) or 0
        a["cache_read"] += u.get("cache_read_input_tokens", 0) or 0
        a["cache_write"] += u.get("cache_creation_input_tokens", 0) or 0
        if side:
            side_out += u.get("output_tokens", 0) or 0
        else:
            main_out += u.get("output_tokens", 0) or 0
    cost = 0.0
    for fam, a in agg.items():
        pi, po, pc = PRICES.get(fam, (0, 0, 0))
        cost += (a["in"] * pi + a["out"] * po + a["cache_read"] * pc + a["cache_write"] * pi * 1.25) / 1e6
    tot_in = sum(a["in"] + a["cache_read"] + a["cache_write"] for a in agg.values()) or 1
    events = {"attempts": 0, "pass": 0, "fail": 0, "env": 0, "capability": 0, "by_rung": {}, "asked_user": 0}
    ev_file = os.path.join(LEAN_DIR, "events.jsonl")
    if os.path.exists(ev_file):
        for line in open(ev_file, errors="ignore"):
            try:
                e = json.loads(line)
            except Exception:
                continue
            if since_iso and e.get("date", "") < since_iso[:10]:
                continue
            events["attempts"] += 1
            events[e.get("outcome", "fail") if e.get("outcome") in ("pass", "fail") else "fail"] += 1
            if e.get("failure") in ("env", "capability"):
                events[e["failure"]] += 1
            k = str(e.get("rung", "?")); events["by_rung"][k] = events["by_rung"].get(k, 0) + 1
            if e.get("asked_user"):
                events["asked_user"] += 1
    return {
        "events": events,
        "note": "est_cost_usd is a list-price estimate, not billing; output tokens deduplicated by message id",
        "by_model": agg, "sessions": len(sessions), "est_cost_usd": round(cost, 2),
        "cache_read_share": round(sum(a["cache_read"] for a in agg.values()) / tot_in, 3),
        "subagent_output_share": round(side_out / ((main_out + side_out) or 1), 3),
    }


def git(*args, cwd=CACHE):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=120)


def step_telemetry(d):
    meta = d.setdefault("telemetry", {})
    if meta.get("enabled") is False:
        print("TELEMETRY disabled"); return False
    since = meta.get("last_sent")
    usage = collect_usage(since)
    if usage is None:
        print("TELEMETRY skipped (no local transcripts for this project on this machine)"); return False
    rec = {"date": TODAY.isoformat(), "since": since, "host": socket.gethostname(),
           "project": os.path.basename(ROOT), "presets": {k: v.get("version") for k, v in d["presets"].items()}, **usage}
    pending = os.path.join(LEAN_DIR, "telemetry-pending.jsonl")
    os.makedirs(LEAN_DIR, exist_ok=True)
    open(pending, "a").write(json.dumps(rec) + "\n")
    try:
        if not os.path.isdir(os.path.join(CACHE, ".git")):
            os.makedirs(os.path.dirname(CACHE), exist_ok=True)
            r = subprocess.run(["git", "clone", "-q", "--depth", "1", REPO_URL, CACHE], capture_output=True, text=True, timeout=300)
            if r.returncode:
                raise RuntimeError("clone failed")
        if git("pull", "-q", "--rebase", "origin", "main").returncode:
            raise RuntimeError("pull failed")
        out = os.path.join(CACHE, "telemetry", rec["host"], f"{rec['project']}.jsonl")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "a") as fo:
            fo.write(open(pending).read())
        git("add", "-A")
        git("-c", "user.name=LeanFlow telemetry", "-c", "user.email=leanflow@localhost", "commit", "-qm", f"telemetry {rec['host']}/{rec['project']} {TODAY}")
        if git("push", "-q", "origin", "HEAD:main").returncode:
            raise RuntimeError("push failed")
        os.remove(pending)
        meta["last_sent"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        advice = os.path.join(CACHE, "telemetry", "advice", f"{rec['project']}.md")
        local_adv = os.path.join(ROOT, ".claude", "lean-advice.md")
        if os.path.exists(advice):
            shutil.copyfile(advice, local_adv)
            print(f"TELEMETRY sent; ADVICE present (.claude/lean-advice.md)"); return True
        if os.path.exists(local_adv):
            os.remove(local_adv)
        print(f"TELEMETRY sent sessions={usage['sessions']} est_cost_usd={usage['est_cost_usd']}")
    except Exception as e:
        print(f"TELEMETRY kept locally ({e}); will retry next week")
    return False


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "weekly"
    force = "--force" in sys.argv
    d = load_versions()
    if cmd == "clean":
        return step_clean()
    if cmd == "version":
        return step_version(d)
    if cmd == "telemetry":
        step_telemetry(d); return save_versions(d)
    if not d["presets"]:
        print("ACTION RUN lean-code (no LeanFlow preset recorded in this project)"); return
    if not force and all(days_since(e.get("last_check", "")) < e.get("check_every_days", 7) or e.get("check_every_days", 7) == 0
                         for e in d["presets"].values()):
        print("SKIP checked recently"); print("ACTION NONE"); return
    step_clean()
    behind = step_version(d)
    big = step_size()
    advice = step_telemetry(d)
    for e in d["presets"].values():
        e["last_check"] = TODAY.isoformat()
    save_versions(d)
    if behind:
        print(f"ACTION RUN {behind[0]} (preset behind)")
    elif advice:
        print("ACTION RUN lean-code (weekly loop left advice in .claude/lean-advice.md)")
    elif big:
        print("ACTION RUN init (CLAUDE.md over 200 lines)")
    else:
        print("ACTION NONE")


if __name__ == "__main__":
    main()
