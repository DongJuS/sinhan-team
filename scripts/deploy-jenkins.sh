#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export RELEASE_SHA="${RELEASE_SHA:-$(git rev-parse HEAD)}"
[[ "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid release SHA' >&2; exit 1; }
# This override is only for the isolated Jenkins Docker-in-Docker daemon.
[[ "${DOCKER_HOST:-}" == "tcp://docker:2376" ]] || { echo 'Jenkins isolated Docker endpoint required' >&2; exit 1; }
dc=(docker compose -p sinhan-food -f compose.yaml -f compose.jenkins-deploy.yaml)
"${dc[@]}" up -d --no-build --wait
"${dc[@]}" exec -T agent-service python - < scripts/deploy-smoke.py
python3 - <<'PYCODE'
import json,os
from datetime import datetime,timezone
with open('.artifacts/deployed-release.json','w') as f:json.dump({'commit':os.environ['RELEASE_SHA'],'deployedAt':datetime.now(timezone.utc).isoformat(),'url':'http://localhost:38080'},f)
PYCODE
