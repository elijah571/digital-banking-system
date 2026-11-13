#!/usr/bin/env bash
# One complete throwaway run of the smoke test, as one command:
#   a git worktree at a commit, its own compose project (-p smoke, fresh volumes), container name overrides,
#   a notification-log follower, the smoke test, the reconciliation check, then down -v and a check that the dev volumes
#   still exist. The dev stack and its volumes are never touched. A trap tears the run down even if a step fails.
#
#   scripts/smoke-throwaway.sh [commit]        (default: HEAD)
#
# Needs: git, docker compose, curl, jq, and bankend/.env (read for the admin login; its values are never printed).
# Output: a directory under /tmp/smoke-throwaway.* with smoke.log, reconciliation.json, and down.log.
set -uo pipefail

COMMIT=${1:-HEAD}
BANKEND=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(git -C "$BANKEND" rev-parse --show-toplevel)
REL=$(git -C "$BANKEND" rev-parse --show-prefix)          # "bankend/" inside the repository
PROJECT=smoke
OUT=$(mktemp -d /tmp/smoke-throwaway.XXXXXX)
WT=$(mktemp -d /tmp/smoke-src.XXXXXX) && rmdir "$WT"       # git worktree add needs a path that does not exist yet
OVERRIDE="$OUT/compose.override.yaml"
TRACE="$OUT/verify-trace.tsv"
FOLLOWER=""
GATEWAY_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
export GATEWAY_PORT
DEV_BEFORE=$(docker volume ls --format '{{.Name}}' | grep -E '^digital-banking-system_mysql' | sort || true)

# Evidence, kept before every teardown, pass or fail: all service logs, container state, and this stack's auth_tokens rows.
# The token rows hold SHA-256 hashes only; raw tokens are never stored.
capture_evidence() {
  local dc=(docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE")
  (cd "$WT/$REL" && "${dc[@]}" ps --all) > "$OUT/compose-ps.txt" 2>&1
  (cd "$WT/$REL" && "${dc[@]}" logs --no-color --timestamps) > "$OUT/services.log" 2>&1
  for svc in mysql redis kafka account-service wallet-service transaction-service payment-service fraud-detection-service \
    auth-service notification-service api-gateway; do
    (cd "$WT/$REL" && "${dc[@]}" logs --no-color --timestamps "$svc") > "$OUT/$svc.log" 2>&1
    container_id=$(cd "$WT/$REL" && "${dc[@]}" ps -aq "$svc")
    if [[ -n "$container_id" ]]; then
      docker inspect --format '{{.Name}} state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}{{if .State.Health}} health={{.State.Health.Status}}{{range .State.Health.Log}} check={{.ExitCode}}:{{.Output}}{{end}}{{end}}' \
        "$container_id" >> "$OUT/container-health.txt" 2>&1
    fi
  done
  (cd "$WT/$REL" && "${dc[@]}" exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t auth_db -e "SELECT u.email, u.email_verified, t.type, t.token_hash, t.created_at, t.expires_at, t.used_at FROM auth_tokens t JOIN users u ON u.id = t.user_id ORDER BY u.email, t.created_at"') > "$OUT/auth-tokens.txt" 2>&1
}

cleanup() {
  [[ -n "$FOLLOWER" ]] && kill "$FOLLOWER" 2>/dev/null
  if [[ -d "$WT/$REL" ]]; then
    capture_evidence
    (cd "$WT/$REL" && docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE" down -v) > "$OUT/down.log" 2>&1
  fi
  git -C "$ROOT" worktree remove --force "$WT" 2>/dev/null
  git -C "$ROOT" worktree prune
  local smoke_left dev_after
  smoke_left=$(docker volume ls --format '{{.Name}}' | grep -c "^${PROJECT}_" || true)
  dev_after=$(docker volume ls --format '{{.Name}}' | grep -E '^digital-banking-system_mysql' | sort || true)
  echo
  echo "teardown: smoke volumes left = $smoke_left"
  if [[ "$DEV_BEFORE" == "$dev_after" && -n "$dev_after" ]]; then
    echo "dev volumes: present and unchanged"
  else
    echo "dev volumes: CHECK THEM (before: $(echo $DEV_BEFORE), after: $(echo $dev_after))"
  fi
  echo "output: $OUT"
}
trap cleanup EXIT

# 1. The worktree at the commit, and this bankend's .env (gitignored) copied into it for compose.
git -C "$ROOT" worktree add --detach "$WT" "$COMMIT" > "$OUT/worktree.log" 2>&1 || { echo "worktree failed"; exit 2; }
[[ -f "$BANKEND/.env" ]] || { echo "bankend/.env is missing"; exit 2; }
cp "$BANKEND/.env" "$WT/$REL.env"
echo "commit: $(git -C "$WT" log --oneline -1)"

# 2. Container name overrides, so the throwaway containers do not collide with the stopped dev containers.
printf '%s\n' 'services:' '  mysql:' '    container_name: smoke-mysql' '    ports: !reset []' \
  '  redis:' '    container_name: smoke-redis' '    ports: !reset []' \
  '  kafka:' '    container_name: smoke-kafka' '    ports: !reset []' '    healthcheck:' \
  '      test: ["CMD-SHELL", "bash -c '\''echo > /dev/tcp/127.0.0.1/9092'\''"]' '      timeout: 5s' \
  '      start_period: 30s' '  account-service:' '    ports: !reset []' \
  '  wallet-service:' '    ports: !reset []' '    environment:' '      WITHDRAWAL_DAILY_MAX: "5000000.00"' \
  '  transaction-service:' '    ports: !reset []' '  fraud-detection-service:' '    ports: !reset []' \
  '  payment-service:' '    ports: !reset []' '  auth-service:' '    ports: !reset []' '    environment:' \
  '      APP_PUBLIC_URL: "http://127.0.0.1:${GATEWAY_PORT}"' \
  '  notification-service:' '    ports: !reset []' '  api-gateway:' '    ports: !override' \
  '      - "127.0.0.1:${GATEWAY_PORT}:8080"' > "$OVERRIDE"

cd "$WT/$REL" || exit 2
COMPOSE=(docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE")

# 3. Build first, then start infrastructure and application services serially. This avoids cold-start CPU contention
# with the dev broker and other services; healthchecks remain unchanged.
"${COMPOSE[@]}" build > "$OUT/build.log" 2>&1 || { echo "build failed: see $OUT/build.log"; exit 2; }
"${COMPOSE[@]}" up -d --wait --wait-timeout 300 mysql redis kafka > "$OUT/infrastructure-up.log" 2>&1 \
  || { echo "infrastructure failed: see $OUT/infrastructure-up.log"; exit 2; }
for svc in account-service wallet-service auth-service notification-service payment-service fraud-detection-service transaction-service api-gateway; do
  "${COMPOSE[@]}" up -d --no-deps --wait --wait-timeout 300 "$svc" > "$OUT/$svc-start.log" 2>&1 \
    || { echo "$svc failed to become healthy: see $OUT/$svc-start.log"; exit 2; }
done
for _ in $(seq 1 90); do
  [[ $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_PORT/actuator/health") == 200 ]] && break
  sleep 5
done
echo "gateway: $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_PORT/actuator/health")"

# 4. The notification log, followed into a file for the smoke test. The PID is kept, so the kill stops only this process.
docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE" logs -f --no-log-prefix --timestamps notification-service > "$OUT/notification.log" 2>&1 &
FOLLOWER=$!
sleep 2

# 5. The smoke test, through the gateway. Each verify call is traced to $TRACE (see verify_call in smoke-test.sh).
printf 'call\ttoken_empty\ttoken_len\ttoken_sha256\tstatus\tbody\trequest_sent\temail_logged\tverify_sent\tto_lines_after_mark\tlink_lines_after_mark\n' > "$TRACE"
NOTIFICATION_LOG="$OUT/notification.log" TRACE_FILE="$TRACE" BASE_URL="http://127.0.0.1:$GATEWAY_PORT" ./scripts/smoke-test.sh > "$OUT/smoke.log" 2>&1
SMOKE_RC=$?
grep -E '^Result' "$OUT/smoke.log" | sed 's/\x1b\[[0-9;]*m//g'

# 6. The reconciliation check, as the admin. The login values come from .env and are not printed.
ADMIN_EMAIL=$(grep -E '^ADMIN_EMAIL=' "$BANKEND/.env" | tail -1 | cut -d= -f2-)
ADMIN_PASSWORD=$(grep -E '^ADMIN_PASSWORD=' "$BANKEND/.env" | tail -1 | cut -d= -f2-)
TOKEN=$(curl -s -X POST "http://127.0.0.1:$GATEWAY_PORT/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p}')" | jq -r '.accessToken // empty')
curl -s "http://127.0.0.1:$GATEWAY_PORT/api/v1/admin/ledger/reconciliation" -H "Authorization: Bearer $TOKEN" > "$OUT/reconciliation.json"
echo "reconciliation: $(jq -c '{clean, issues}' "$OUT/reconciliation.json" 2>/dev/null || echo 'no answer')"
echo "smoke exit: $SMOKE_RC"
exit "$SMOKE_RC"
