#!/usr/bin/env bash
# Build everything locally with the Maven wrappers (for running services from the IDE / terminal).
# Docker does NOT need this: the Dockerfiles are multi-stage, `docker compose up -d --build` builds inside Docker.
#   ./scripts/build.sh            # shared lib + all service jars (tests skipped)
#   ./scripts/build.sh --test     # same, with all unit + integration tests (needs Docker for Testcontainers)
set -euo pipefail
cd "$(dirname "$0")/.."
SKIP=(-DskipTests)
[[ "${1:-}" == "--test" ]] && SKIP=()
echo "==> Building libs/banking-common"
libs/banking-common/mvnw -q -B -f libs/banking-common/pom.xml "${SKIP[@]}" install   # services depend on it
for s in auth-service account-service transaction-service fraud-detection-service payment-service notification-service API-GATEWAY; do
  echo "==> Building $s"
  "$s/mvnw" -q -B -f "$s/pom.xml" "${SKIP[@]}" verify
done
