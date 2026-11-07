# Weekly self-check (lean-code preset)

This project was set up with the LeanFlow `lean-code` preset on 2026-11-07. The preset changes when Anthropic ships new models, effort levels or pricing; the LeanFlow plugin on the account syncs those changes automatically. This check makes the project follow them, cheaply.

Once a week (the CLAUDE.md block asks for it at the start of the first session of the week):

1. Read `.claude/lean-version.json`, entry `lean-code`. If `last_check` is less than `check_every_days` ago, stop: nothing to do.
2. Invoke the `lean-code` skill. Its Phase 0 compares this project's version with the synced plugin's version, entirely locally:
   - same version and nothing missing: it updates `last_check` and stops (one skill load, no other work);
   - plugin newer: it shows the diff and, with the user's approval, applies only that;
   - something removed or edited by hand: it reports it and offers to restore only that.
3. If the `lean-code` skill is not available in this session, update `last_check` and stop; try next week.

Never delete this file. To stop the weekly check, set `check_every_days` to 0 in `.claude/lean-version.json`.
