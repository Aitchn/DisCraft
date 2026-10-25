#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f /workspace/discraft-env/activate.sh ]]; then
  source /workspace/discraft-env/activate.sh
  use_jdk 17
fi
./gradlew -Pforge :core:build :forge:build --console=plain
if [[ "${1:-}" == "--client" ]]; then
  # Remove only the previous test summary, so a failed launch cannot reuse PASS.
  rm -f build/evidence/minecraft/result.txt
  if command -v headless-run >/dev/null; then
    timeout 600 headless-run env JAVA_HOME="$JAVA_HOME" ./gradlew -Pforge -Psmoke :forge:runClient --console=plain
  else
    timeout 600 ./gradlew -Pforge -Psmoke :forge:runClient --console=plain
  fi
  python3 - <<'PY'
from pathlib import Path
result = Path('build/evidence/minecraft/result.txt').read_text()
print(result)
assert result.splitlines()[-1].startswith('PASS '), 'Minecraft acceptance failed'
PY
fi
