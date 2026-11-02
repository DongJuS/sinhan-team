#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# The image uses the same LangGraph/Ollama code that will be deployed.
# The 16 GB model remains on the Mac and is not copied into this container.
docker compose run --rm --no-deps -e AI_MODE=local \
  -e OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://host.docker.internal:11435}" \
  -e OLLAMA_MODEL="${OLLAMA_MODEL:-sinhan-qwen:14b-q8_0}" \
  -v "$PWD/tests:/checks:ro" \
  agent-service python /checks/evaluate_local_ai.py
