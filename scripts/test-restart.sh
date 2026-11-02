#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .artifacts
dc=(docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml)
"${dc[@]}" --profile test run --rm --no-deps -v "$PWD/.artifacts:/artifacts" tests python restart_probe.py prepare
"${dc[@]}" kill -s SIGKILL order-worker
"${dc[@]}" up -d --no-deps order-worker
"${dc[@]}" --profile test run --rm --no-deps -v "$PWD/.artifacts:/artifacts" tests python restart_probe.py verify
