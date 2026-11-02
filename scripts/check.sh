#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp test
python -m pytest -q
docker compose config --quiet
