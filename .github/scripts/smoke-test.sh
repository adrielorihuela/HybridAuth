#!/usr/bin/env bash
# Starts the proxy jar once, in an empty directory, and checks it really comes up: the boot line,
# the authentication server listening, and the proxy reaching Done. The build and its tests can
# all pass with a proxy that does not start, which is why updating-upstreams.md asks for this.
#
# Usage: smoke-test.sh <path to HybridAuth-<version>.jar> [timeout seconds, default 120]
# The configuration comes from the repository this script is in, unless REPO_ROOT names another:
# upstream.yml runs a copy taken before Claude could edit anything.
#
# The configuration is the shipped one with the [plugins] lists emptied, so the run downloads
# nothing and does not depend on Hangar, Modrinth or GitHub being up.
set -euo pipefail

jar="$(realpath "${1:?path to the proxy jar}")"
timeout="${2:-120}"
here="${REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
work="$(mktemp -d)"
trap 'kill "${pid:-0}" 2>/dev/null || true; rm -rf "${work}"' EXIT

mkdir -p "${work}/config"
python3 -I - "${here}/proxy/src/main/resources/default-velocity.toml" \
    "${work}/config/HybridAuth.toml" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for key in ("hangar", "modrinth", "github", "geyser"):
    text = re.sub(rf"^{key} = \[[^\]]*\]", f"{key} = []", text, flags=re.M)
open(sys.argv[2], "w", encoding="utf-8").write(text)
PY

cd "${work}"
java -Xmx512M -jar "${jar}" > proxy.log 2>&1 < /dev/null &
pid=$!

for _ in $(seq "${timeout}"); do
  if grep -q 'Done (' proxy.log || ! kill -0 "${pid}" 2>/dev/null; then
    break
  fi
  sleep 1
done
sleep 2

status=0
for expected in 'Booting up HybridAuth' '[auth]: Server started on' 'Done ('; do
  if grep -qF "${expected}" proxy.log; then
    echo "ok:      ${expected}"
  else
    echo "missing: ${expected}"
    status=1
  fi
done
if [ "${status}" -ne 0 ]; then
  echo "---- proxy log ----"
  cat proxy.log
fi
exit "${status}"
