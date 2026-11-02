#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${DOCKER_HOST:-}" == "tcp://docker:2376" ]] || { echo 'Jenkins isolated Docker endpoint required' >&2; exit 1; }
mkdir -p .artifacts
running=()
others=()
postgres_id=""
keycloak_id=""
while IFS= read -r id; do
  [[ -n "$id" ]] || continue
  running+=("$id")
  service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id")
  case "$service" in
    postgres) postgres_id="$id" ;;
    keycloak) keycloak_id="$id" ;;
    *) others+=("$id") ;;
  esac
done < <(docker ps -q --filter label=com.docker.compose.project=sinhan-food --filter label=com.docker.compose.oneoff=False)

wait_ready() {
  local id="$1" state
  for _ in $(seq 1 60); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
    [[ "$state" == healthy || "$state" == running ]] && return 0
    [[ "$state" == exited || "$state" == dead ]] && return 1
    sleep 1
  done
  return 1
}

cleanup() {
  result=$?
  trap - EXIT
  set +e
  docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml logs --no-color --tail 150 > .artifacts/integration-run-services.log 2>&1
  docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml down --volumes --remove-orphans || result=1
  # Restart only the containers that were running before this script; retain their exact image/config.
  if [[ -n "$postgres_id" ]]; then
    docker start "$postgres_id" >/dev/null && wait_ready "$postgres_id" || result=1
  fi
  if [[ -n "$keycloak_id" ]]; then
    docker start "$keycloak_id" >/dev/null && wait_ready "$keycloak_id" || result=1
  fi
  if (( ${#others[@]} )); then docker start "${others[@]}" >/dev/null || result=1; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if (( ${#running[@]} )); then
  echo 'Pausing local demo containers while isolated integration tests run; volumes are retained.'
  docker stop --time 10 "${running[@]}" >/dev/null
fi
./scripts/test-integration.sh
