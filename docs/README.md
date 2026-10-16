# HybridAuth documentation

Everything this fork adds on top of upstream [Velocity](https://docs.papermc.io/velocity). Anything
not covered here behaves exactly as it does upstream, so the Velocity documentation still applies.

The main thing it adds is an authentication layer for proxies running in offline mode — a mode
Velocity already supports, made stricter rather than looser. Alongside it: an automatic plugin
updater, a command per server, and Nether and End portals that lead to another server.
Verification of premium players by Mojang is untouched.

## [guide/](guide/) — using the proxy

Start here if you want to run it.

| Document | |
| --- | --- |
| [getting-started.md](guide/getting-started.md) | Install, first run, upgrading from stock Velocity, and what is on by default. |
| [offline-auth.md](guide/offline-auth.md) | The register/login gate: options, what a player sees, and what to back up. |
| [server-commands.md](guide/server-commands.md) | The `comandos` option: a command per server, and its permissions. |
| [portal-redirects.md](guide/portal-redirects.md) | The `[portales]` section: Nether and End portals that send players to another server. |
| [hybrid-offline-profiles.md](guide/hybrid-offline-profiles.md) | How players Mojang cannot verify are given an identity. |

## [development/](development/) — how it was built

Why the fork works the way it does: the specification, the protocol research, the failed first
attempt and what it taught us — and how it is released and kept up to date with upstream. Read this before changing the authentication code — most of it exists
to stop a specific mistake being repeated.

| Document | |
| --- | --- |
| [offline-auth-specification.md](development/offline-auth-specification.md) | The original functional specification the feature implements. |
| [offline-auth-postmortem.md](development/offline-auth-postmortem.md) | The first attempt, how the client failed, and the six causes. |
| [offline-auth-requirements.md](development/offline-auth-requirements.md) | What the vanilla client demands per version: configuration phase, registries and tags, the PLAY spawn sequence, KeepAlive and timeouts. |
| [offline-auth-plan.md](development/offline-auth-plan.md) | The architecture that was chosen, and the alternatives rejected. |
| [auth-server.md](development/auth-server.md) | The vendored NanoLimbo subtree: layout and local patches. |
| [updating-upstreams.md](development/updating-upstreams.md) | How changes from Velocity and NanoLimbo arrive: the hourly workflow, Claude resolving what needs judgment in CI, and doing it by hand. |
| [releasing.md](development/releasing.md) | Every change that reaches `main` is released: how the version and the notes are chosen. |
| [discarded/](development/discarded/) | Superseded analysis, kept for the reasoning it records. |
