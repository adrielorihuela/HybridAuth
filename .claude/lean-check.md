# Weekly self-check (LeanFlow)

One command, no LLM work unless something needs it:

```bash
python3 .claude/lean/lean_maint.py weekly
```

It skips itself if run in the last 7 days. Otherwise it:

1. Cleans LeanFlow by-products: `.bak` files older than 14 days, `.claude/logs` older than 7 days (folder capped at 50 MB), a stale setup plan.
2. Compares this project's preset version with the LeanFlow plugin installed on this machine (synced from the repo).
3. Flags CLAUDE.md over 200 lines.
4. Sends token usage by model since the last report to the LeanFlow repo (`telemetry/<host>/<project>.jsonl`) and brings back any advice the weekly loop wrote for this project into `.claude/lean-advice.md`. If the push fails, the record stays in `.claude/lean/telemetry-pending.jsonl` and goes next week.

Read only the last line:

- `ACTION NONE`: done.
- `ACTION RUN lean-code` or `lean-research`: invoke that skill; its Phase 0 applies only the difference (or the advice).
- `ACTION RUN init`: run `/init` to re-split CLAUDE.md.

To stop telemetry for this project: set `"telemetry": {"enabled": false}` in `.claude/lean-version.json`. To stop the weekly check: set `check_every_days` to 0.
