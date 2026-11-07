---
paths:
  - "proxy/src/main/java/com/velocitypowered/proxy/connection/**"
  - "proxy/src/main/java/com/velocitypowered/proxy/protocol/**"
  - "proxy/src/main/java/com/velocitypowered/proxy/network/**"
---

<!-- Moved from CLAUDE.md § Architecture › Connection lifecycle on 2026-11-07 -->

### Connection lifecycle

A player connection is a Netty channel wrapped in `MinecraftConnection`, which delegates every
packet to a single **active session handler** (`MinecraftSessionHandler`). Progressing through
the protocol means swapping that handler via
`MinecraftConnection#setActiveSessionHandler(StateRegistry, handler)` — this both installs the
handler and switches the packet registry, and must run on the connection's event loop.

The client-side chain is `HandshakeSessionHandler` → `StatusSessionHandler` (ping) or
`InitialLoginSessionHandler` (encryption + Mojang auth) → `AuthSessionHandler` (builds the
`ConnectedPlayer`) → `ClientConfigSessionHandler` / `ClientPlaySessionHandler`. The proxy holds
a second connection per player to the backend server (`VelocityServerConnection`) with its own
chain in `connection/backend/`: `LoginSessionHandler` → `TransitionSessionHandler` →
`BackendPlaySessionHandler`.

`StateRegistry` (`protocol/StateRegistry.java`) is the single source of truth mapping packet
IDs to classes per protocol state and per Minecraft version. Adding or changing a packet means
editing the relevant state's `serverbound`/`clientbound` registrations there, keyed by
`ProtocolVersion` ranges. The Netty pipeline in `protocol/netty/` handles framing, compression
and encryption around it.

Because everything runs on Netty event loops, blocking work must be dispatched off-loop
(see `VelocityScheduler`), and code touching a connection must respect its event loop.
