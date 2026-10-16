<!--
  Template for the notes of a release made by the upstream update
  (.github/workflows/upstream.yml). .github/scripts/render-notes.py fills in the {{placeholders}}.

  Automatic: every check passed, so the update was merged and released without anyone.
  Manual: something needed judgment. The update waits on a branch with these notes drafted;
  whoever resolves it fills in the MANUAL section below and rewrites the rest where it no longer
  holds. See docs/development/updating-upstreams.md.
-->
Brings the fork up to **Velocity {{velocity_version}}**{{limbo_clause}}. {{intro_tail}}

## From upstream

{{velocity_section}}

{{limbo_section}}

## From this fork

{{fork_section}}

## How this release was made

{{how}}

## Upgrading from {{previous}}

Replace the jar. Nothing else changes.
