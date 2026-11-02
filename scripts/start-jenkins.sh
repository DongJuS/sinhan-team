#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .ci-secrets
chmod 700 .ci-secrets
if [[ ! -f .ci-secrets/jenkins_admin_password ]]; then
  python3 - <<'PYCODE'
import os, secrets
fd=os.open('.ci-secrets/jenkins_admin_password',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:f.write(secrets.token_urlsafe(32))
PYCODE
fi
docker compose -f compose.jenkins.yaml up -d --build --wait
printf '%s\n' 'Jenkins: http://localhost:18090' 'User: admin' 'Password file: .ci-secrets/jenkins_admin_password' 'Pipeline: sinhan-team'
