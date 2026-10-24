#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# The cloud helper defaults to JDK21; this target deliberately uses JDK17.
if [[ -f /workspace/discraft-env/activate.sh ]]; then
  source /workspace/discraft-env/activate.sh
  use_jdk 17
fi
./gradlew --offline :core:build --console=plain
if [[ "${1:-}" == "--ui" ]]; then
  if command -v headless-run >/dev/null; then
    headless-run env JAVA_HOME="${JAVA_HOME}" ./gradlew --offline :core:previewSmoke --console=plain
  elif command -v xvfb-run >/dev/null; then
    xvfb-run -a -s '-screen 0 1280x720x24 -nolisten tcp' ./gradlew --offline :core:previewSmoke --console=plain
  else
    ./gradlew --offline :core:previewSmoke --console=plain
  fi
fi
