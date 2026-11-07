---
paths:
  - "proxy/src/main/java/com/velocitypowered/proxy/command/builtin/BuiltinCommandUtil.java"
  - "proxy/src/main/java/com/velocitypowered/proxy/config/ConfigurationLocation.java"
  - "proxy/src/test/java/com/velocitypowered/proxy/command/**"
  - "proxy/src/test/java/com/velocitypowered/proxy/config/**"
  - "proxy/src/main/resources/com/velocitypowered/proxy/l10n/**"
---

<!-- Moved from CLAUDE.md § Architecture › Renames, and the literals that must survive them on 2026-11-07 -->

### Renames, and the literals that must survive them

The project has been renamed twice (`velocity` → `hybridvelocity` → `hybridauth`) and the
configuration file three times. Both renames were done with a tree-wide search and replace, and
**both times that quietly broke the compatibility they were supposed to preserve** by rewriting the
old name inside the fallback list as well. Tests caught it; a person would not have.

So: `LEGACY_PERMISSION_PREFIXES` in `BuiltinCommandUtil` (`"velocity.command."`) and
`PREVIOUS_NAMES` in `ConfigurationLocation` (`"proxy.toml"`, `"velocity.toml"`) hold **string
literals of names this project no longer uses**. They are correct precisely because they are out of
date, and a search and replace must never touch them. There are tests pinning both lists, and they
are the only thing standing between a rename and somebody's granted permissions silently
disappearing.

1.3.1 deliberately shortened both lists, which is not the same thing as a rename breaking them: the
fork's own intermediate name is gone everywhere — the `hybridvelocity` command alias,
`hybridvelocity.command.*` and `hybridvelocity.toml` — along with the `/ha` and `/hv` shortcuts.
Only `hybridauth` and upstream's `velocity` remain. Do not add the intermediate name back as an
alias, a prefix or a file name, and do not drop `velocity` from either list: that is what keeps a
stock Velocity installation working when this jar is dropped into it.
