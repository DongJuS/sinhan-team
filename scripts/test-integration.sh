#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Only the dedicated sinhan-food-test project is reset. Normal demo volumes are preserved.
docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml down --volumes --remove-orphans
docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml up -d --build --wait
docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml --profile test run --rm --build tests
./scripts/test-restart.sh
