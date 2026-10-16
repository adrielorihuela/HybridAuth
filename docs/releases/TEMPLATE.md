<!--
  Template for the notes of a release published automatically when a change reaches main.
  .github/scripts/render-notes.py fills in the {{placeholders}}; the result is committed as
  docs/releases/<version>.md and used as the release body. Hand-written notes do not use this: a
  release cut by hand brings its own docs/releases/<version>.md. See docs/development/releasing.md.
-->
{{intro}}

{{changes}}

**Full comparison:** {{compare}}

## How this release was made

**Automatically.** {{trigger}} reached `main`, and every change that reaches `main` is released, so
nobody had to cut this one. The version comes from the commits since {{previous}}: {{bump_reason}}.
`./gradlew build` passed — compilation, Checkstyle, Spotless and {{tests}} tests — before the jar
below was attached. These notes were generated from `docs/releases/TEMPLATE.md`.

## Upgrading from {{previous}}

Replace the jar. {{upgrade_note}}
