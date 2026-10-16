# How HybridAuth was built

The record of the work behind the fork: the specification it set out to satisfy, the protocol
research, the attempt that failed and what it cost, and the architecture that replaced it.

Read this before changing the authentication code. Most of what is written here exists to stop a
specific mistake from being made twice.

| Document | |
| --- | --- |
| [offline-auth-specification.md](offline-auth-specification.md) | The original functional specification: the flow, the exact chat strings, the password rules and the storage requirements. |
| [offline-auth-postmortem.md](offline-auth-postmortem.md) | The first attempt, the two errors the client reported, and the six causes — only two of which its own notes had found. The branch it lived on is gone, so this is the whole record. |
| [offline-auth-requirements.md](offline-auth-requirements.md) | What the vanilla client actually demands, per version: the configuration phase, the mandatory registries and tags, the PLAY spawn sequence, KeepAlive against the read timeout, and which packets this fork does and does not have. |
| [offline-auth-plan.md](offline-auth-plan.md) | The architecture that was adopted — vendoring a limbo server and running it in-process — and the two alternatives rejected, with the licence and maintenance reasoning. |
| [auth-server.md](auth-server.md) | The vendored NanoLimbo subtree: layout, the local patches and why each exists. |
| [updating-upstreams.md](updating-upstreams.md) | **How to pull changes from Velocity and NanoLimbo**: what the hourly workflow does by itself, where Claude resolves an update in CI and how to enable it, what to do when it stops for review, why merge rather than rebase, and what to check afterwards. |
| [releasing.md](releasing.md) | **Every change that reaches `main` is released**: how the version and the notes are chosen, how to write the notes yourself, and pre-releases. |
| [discarded/](discarded/) | Analysis that was superseded. Kept so the options it rejects are not proposed again. |

## The short version

Offline players need somewhere to wait that is not a real server. Synthesising that inside the proxy
means writing and forever maintaining per-version protocol code — the first attempt died on exactly
that, sending an empty configuration phase and never sending `Login (play)` at all. So the fork
embeds an existing limbo server instead, vendored as a subtree, and keeps its own patch surface to
two files.
