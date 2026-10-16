#!/usr/bin/env bash
# Looks at what an upstream update changed and lists everything that needs judgment rather than a
# script: each is a way a Velocity or NanoLimbo update has broken this fork, or could, without the
# build failing. docs/development/updating-upstreams.md explains each one.
#
# Usage: review-checks.sh <base> <head>   (main before the update, and after it)
# Prints one reason per line; prints nothing when the update needs nobody.
set -euo pipefail

base="${1:?base}"
head="${2:?head}"

changed() { ! git diff --quiet "${base}" "${head}" -- "$@"; }

# Lines a diff adds or removes, without the file headers.
diff_lines() { git diff -U0 "${base}" "${head}" -- "$@" | grep -E '^[+-]' | grep -Ev '^(\+\+\+|---) ' || true; }

# 1. config-version is upstream's, and OfflineAuthMigration hands it back to 2.8 when it finds one
#    of the numbers the fork used to write. Upstream moving it is what broke 4.2.0.
if old_new="$(diff_lines proxy/src/main/resources/default-velocity.toml | grep -E '^[+-]config-version' | tr '\n' ' ')" \
    && [ -n "${old_new}" ]; then
  echo "config-version changed in default-velocity.toml (${old_new% }): check OfflineAuthMigration's hand-back against the new number, and that the upstream migration that comes with it runs once"
fi

# 2. A new Minecraft version. The authentication server speaks its own copy of the protocol, from
#    the NanoLimbo subtree, and does not learn it from Velocity.
versions() {
  git show "$1:api/src/main/java/com/velocitypowered/api/network/ProtocolVersion.java" \
    | grep -Eo '^\s*MINECRAFT_[0-9_]+' | tr -d ' ' | sort -u
}
added="$(comm -13 <(versions "${base}") <(versions "${head}") | tr '\n' ' ')"
if [ -n "${added}" ]; then
  echo "New Minecraft version(s) in ProtocolVersion: ${added% }. The authentication server (limbo/upstream) must support them, or offline players on those clients depend on ViaVersion"
fi

# 3. The events the authentication gate is built from. A changed signature disables it quietly:
#    the proxy still starts.
for event in ServerPreConnectEvent PlayerAvailableCommandsEvent CommandExecuteEvent \
    PermissionsSetupEvent PostLoginEvent PreLoginEvent; do
  file="$(git ls-tree -r --name-only "${head}" api/src/main/java | grep "/${event}.java$" || true)"
  [ -n "${file}" ] || continue
  code="$(diff_lines "${file}" | grep -Ev '^[+-]\s*($|import |package |//|\*|/\*)' || true)"
  # Writing a type out in full or importing it is the same code: 4.2.0 replaced
  # net.kyori.adventure.text.Component with Component throughout PreLoginEvent and nothing else.
  if [ -n "${code}" ] && ! python3 -I - "${code}" <<'PY'
import re, sys
# Whole sides, without whitespace (so rewrapping counts as nothing) or package prefixes (including
# the net.kyori.adventure.text.@Nullable Component form).
side = lambda sign: re.sub(r"\s+", "", re.sub(r"\b(?:[a-z][a-z0-9_]*\.)+(?=[A-Z@])", "", "".join(
    l[1:] for l in sys.argv[1].splitlines() if l.startswith(sign))))
sys.exit(0 if side("-") == side("+") else 1)
PY
  then
    echo "${event} changed beyond imports and comments: the offline-auth gate (auth/OfflineAuthGate.java) listens to it"
  fi
done

# 4. Anything new listing servers will list the authentication server too.
calls="$(diff_lines proxy/src/main | grep -E '^\+.*getAllServers\(' || true)"
if [ -n "${calls}" ]; then
  echo "New getAllServers() call(s) in proxy/src/main: if any lists servers to players, add it to server/InternalServers.java"
fi

# 5. Upstream's CI is not ours. 4.2.0 moved it to Namespace runners this repository cannot use.
if changed .github; then
  echo "The update changes files under .github/: upstream's CI does not apply here (see updating-upstreams.md), keep ours"
fi

# 6. Portals read the backend's respawn from the raw buffer because it is registered encode-only.
#    If upstream starts decoding it, BackendPlaySessionHandler#handle(RespawnPacket) becomes the
#    path that runs; PortalDimensionTest also catches this.
respawn_block() {
  git show "$1:proxy/src/main/java/com/velocitypowered/proxy/protocol/StateRegistry.java" \
    | awk '/RespawnPacket.class,/{on=1} on{print} on&&/\);/{exit}'
}
if respawn_block "${head}" | grep -q 'false)'; then
  echo "StateRegistry now decodes the clientbound RespawnPacket for some versions: check the portal redirect path in BackendPlaySessionHandler"
fi

# 7. NanoLimbo. Its updates always need a person: LIMBO_VERSION, Netty agreeing on both sides, and
#    whether the three local patches survived.
if changed limbo/upstream; then
  echo "NanoLimbo (limbo/upstream) changed: bump LIMBO_VERSION in limbo/src/main/java/ua/nanit/limbo/BuildConfig.java, compare its libs.versions.toml with ours, check auth-server-settings.yml for new options"
  before="$(git grep -c 'HybridAuth patch:' "${base}" -- limbo/upstream | wc -l)"
  after="$(git grep -c 'HybridAuth patch:' "${head}" -- limbo/upstream | wc -l)"
  if [ "${after}" -lt "${before}" ]; then
    echo "Files carrying 'HybridAuth patch:' markers under limbo/upstream went from ${before} to ${after}: a local patch was lost (docs/development/auth-server.md)"
  fi
fi
