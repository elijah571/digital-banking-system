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
DEV_BEFORE=$(docker volume ls --format '{{.Name}}' | grep -E '^digital-banking-system_mysql' | sort || true)

# Evidence, kept before every teardown, pass or fail (wallet-plan 8.29): both services' logs, and this stack's auth_tokens rows.
# The token rows hold SHA-256 hashes only; raw tokens are never stored.
capture_evidence() {
  local dc=(docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE")
  (cd "$WT/$REL" && "${dc[@]}" logs --no-color --timestamps auth-service) > "$OUT/auth-service.log" 2>&1
  (cd "$WT/$REL" && "${dc[@]}" logs --no-color --timestamps notification-service) > "$OUT/notification-service.log" 2>&1
  # The transfer and PIN path: kept so a failure in any of these services can be read after teardown.
  for svc in wallet-service transaction-service api-gateway; do
    (cd "$WT/$REL" && "${dc[@]}" logs --no-color --timestamps "$svc") > "$OUT/$svc.log" 2>&1
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
printf '%s\n' 'services:' '  mysql:' '    container_name: smoke-mysql' '  redis:' '    container_name: smoke-redis' \
  '  kafka:' '    container_name: smoke-kafka' > "$OVERRIDE"

cd "$WT/$REL" || exit 2

# 3. Build and start the stack in its own project. Named volumes are prefixed with the project name, so they are fresh.
docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE" up -d --build > "$OUT/up.log" 2>&1 || { echo "up failed: see $OUT/up.log"; exit 2; }
for _ in $(seq 1 90); do
  [[ $(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health) == 200 ]] && break
  sleep 5
done
echo "gateway: $(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health)"

# 4. The notification log, followed into a file for the smoke test. The PID is kept, so the kill stops only this process.
docker compose -p "$PROJECT" -f compose.yaml -f "$OVERRIDE" logs -f --no-log-prefix --timestamps notification-service > "$OUT/notification.log" 2>&1 &
FOLLOWER=$!
sleep 2

# 5. The smoke test, through the gateway. Each verify call is traced to $TRACE (see verify_call in smoke-test.sh).
printf 'call\ttoken_empty\ttoken_len\ttoken_sha256\tstatus\tbody\trequest_sent\temail_logged\tverify_sent\tto_lines_after_mark\tlink_lines_after_mark\n' > "$TRACE"
NOTIFICATION_LOG="$OUT/notification.log" TRACE_FILE="$TRACE" BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh > "$OUT/smoke.log" 2>&1
SMOKE_RC=$?
grep -E '^Result' "$OUT/smoke.log" | sed 's/\x1b\[[0-9;]*m//g'

# 6. The reconciliation check, as the admin. The login values come from .env and are not printed.
ADMIN_EMAIL=$(grep -E '^ADMIN_EMAIL=' "$BANKEND/.env" | tail -1 | cut -d= -f2-)
ADMIN_PASSWORD=$(grep -E '^ADMIN_PASSWORD=' "$BANKEND/.env" | tail -1 | cut -d= -f2-)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p}')" | jq -r '.accessToken // empty')
curl -s http://localhost:8080/api/v1/admin/ledger/reconciliation -H "Authorization: Bearer $TOKEN" > "$OUT/reconciliation.json"
echo "reconciliation: $(jq -c '{clean, issues}' "$OUT/reconciliation.json" 2>/dev/null || echo 'no answer')"
echo "smoke exit: $SMOKE_RC"
exit "$SMOKE_RC"
