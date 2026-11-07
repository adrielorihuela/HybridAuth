---
name: init
description: "Project replacement for the built-in /init. Analyzes the codebase and writes or improves CLAUDE.md in LeanFlow format (orchestrator block first, under 200 lines, procedures in skills, path rules in .claude/rules, reference material in docs), without deleting existing content. Use whenever the user runs /init or asks to generate, regenerate, refresh or improve CLAUDE.md in this project."
---

# /init (LeanFlow version)

Start your answer with the line `LeanFlow /init` so the user can confirm this replacement is the one running.

This project uses the LeanFlow coding preset. Instead of writing a generic CLAUDE.md:

1. If the `lean-code` skill is available, invoke it. Its Phase 0 decides whether the project needs an install, an update or only a verify, and it re-classifies CLAUDE.md content block by block. Stop here.
2. If `lean-code` is not available, do the same by hand, briefly:
   - Read CLAUDE.md, `.claude/rules/`, `.claude/skills/`, `.claude/agents/`, the manifests and CI config.
   - Keep the orchestrator block ("How to work in this repo") at the top unchanged.
   - Update the condensed project facts (stack, structure, commands) from what the codebase shows now.
   - Move any procedure for one kind of task into `.claude/skills/<topic>/SKILL.md`, path-specific rules into `.claude/rules/<topic>.md`, long reference text into `docs/`, each linked once from "Where things live".
   - Keep CLAUDE.md under 200 lines. Delete nothing: back up CLAUDE.md to `CLAUDE.md.bak` before writing.
   - Also read `.cursorrules`, `.github/copilot-instructions.md` and `AGENTS.md` if present and fold in what is relevant, as the built-in /init does.
3. Show the user the diff before writing and wait for approval.
4. In this repository, CLAUDE.md § "Committing is not optional" applies: `/init` ends in a commit and a push.
