#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
release_sha="${RELEASE_SHA:-$(git rev-parse HEAD)}"
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid release SHA' >&2; exit 1; }
images=()
for service in gateway order-service order-worker agent-service benefit-service control-service payment-service merchant-simulator; do
  image="sinhan-team/$service:$release_sha"
  docker tag "sinhan-food-$service:latest" "$image"
  images+=("$image")
done
mkdir -p .artifacts
docker image inspect "${images[@]}" --format '{{json .}}' > .artifacts/release-images.json
