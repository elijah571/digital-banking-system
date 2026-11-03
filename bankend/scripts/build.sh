#!/usr/bin/env bash
# Build all service jars with each service's Maven wrapper, then build the Docker images.
#   ./scripts/build.sh            # jars + images
#   ./scripts/build.sh --jars     # jars only
set -euo pipefail
cd "$(dirname "$0")/.."
for s in auth-service account-service transaction-service fraud-detection-service payment-service notification-service API-GATEWAY; do
  echo "==> Building $s"
  "$s/mvnw" -q -B -f "$s/pom.xml" -DskipTests clean package
done
[[ "${1:-}" == "--jars" ]] && exit 0
docker compose build
