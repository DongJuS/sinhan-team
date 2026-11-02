#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .artifacts .ci-cache/m2 .ci-cache/pip
uid_gid="$(id -u):$(id -g)"
docker compose config --quiet
docker run --rm --user "$uid_gid" -e HOME=/tmp -e MAVEN_CONFIG=/tmp/maven \
  -v "$PWD:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 \
  mvn -B -ntp -Dmaven.repo.local=/workspace/.ci-cache/m2 test
docker run --rm --user "$uid_gid" -e HOME=/tmp -v "$PWD:/workspace" -w /workspace python:3.12-slim \
  sh -ceu 'python -m venv /tmp/checks; /tmp/checks/bin/pip install --cache-dir /workspace/.ci-cache/pip -r services/python-common/requirements.txt -r services/agent-service/requirements.txt pytest==8.3.5 openapi-spec-validator==0.7.2; /tmp/checks/bin/python -m pytest -q --junitxml=.artifacts/python-tests.xml; /tmp/checks/bin/python tests/evaluate_agent.py; /tmp/checks/bin/python -m openapi_spec_validator contracts/public-api.yaml'
