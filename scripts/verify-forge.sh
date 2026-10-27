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
  client_status=0
  if command -v headless-run >/dev/null; then
    timeout 600 headless-run env JAVA_HOME="$JAVA_HOME" ./gradlew -Pforge -Psmoke :forge:runClient --console=plain || client_status=$?
  else
    timeout 600 ./gradlew -Pforge -Psmoke :forge:runClient --console=plain || client_status=$?
  fi
  python3 - "$client_status" <<'PY'
from pathlib import Path
import sys
path=Path('build/evidence/minecraft/result.txt')
result=path.read_text() if path.is_file() else ''
lines=result.splitlines()
if int(sys.argv[1]) or not lines or not lines[-1].startswith('PASS '):
    print('Fixture result: process_exit='+sys.argv[1])
    for line in lines[-12:]:print('FIXTURE_RESULT '+line[:600])
    if not lines:print('FIXTURE_RESULT No authored Minecraft fixture summary was produced')
    raise SystemExit('Minecraft acceptance failed; bounded fixture result is printed above')
print(result)
PY
fi
