#!/usr/bin/env bash
# End-to-end test of every endpoint, through the API gateway, with authentication and roles.
#
#   ./scripts/smoke-test.sh                       # against http://localhost:8080
#   BASE_URL=https://api.example.com ./scripts/smoke-test.sh
#
# Verification links, reset tokens and OTPs are read from the notification-service log:
#   - `docker compose logs notification-service`   (Docker; dev compose sets MAIL_LOG_CONTENT=true), or
#   - $NOTIFICATION_LOG                             (./mvnw runs, e.g. /tmp/notification-service.log)
# Admin credentials come from ADMIN_EMAIL / ADMIN_PASSWORD (environment or ../.env).
#
# Requires: curl, jq
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
NOTIFICATION_LOG="${NOTIFICATION_LOG:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_ID="$(date +%s)"
PASSWORD="Secret123"

env_value() { grep -E "^$1=" "$ROOT_DIR/.env" 2>/dev/null | tail -1 | cut -d= -f2-; }
ADMIN_EMAIL="${ADMIN_EMAIL:-$(env_value ADMIN_EMAIL)}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(env_value ADMIN_PASSWORD)}"

PASS=0; FAIL=0; SKIP=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

# call METHOD PATH [JSON_BODY] [TOKEN] [EXTRA_HEADER]  -> sets $STATUS and $BODY (retries on 429 rate limiting)
call() {
  local method=$1 path=$2 data=${3:-} token=${4:-} extra=${5:-}
  local args=(-s -w $'\n%{http_code}' -X "$method" "$API$path")
  [[ -n "$data" ]]  && args+=(-H 'Content-Type: application/json' -d "$data")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  local out
  for _ in 1 2 3 4 5 6; do
    out=$(curl "${args[@]}")
    STATUS=$(tail -n1 <<<"$out"); BODY=$(sed '$d' <<<"$out")
    [[ "$STATUS" != 429 ]] && return 0
    sleep 1
  done
}

# check NAME EXPECTED_STATUS [JQ_FILTER EXPECTED_VALUE]
check() {
  local name=$1 expected=$2 filter=${3:-} want=${4:-}
  if [[ "$STATUS" != "$expected" ]]; then
    red "FAIL  $name — expected HTTP $expected, got $STATUS: ${BODY:0:200}"; FAIL=$((FAIL+1)); return 1
  fi
  if [[ -n "$filter" ]]; then
    local got; got=$(jq -r "$filter" <<<"$BODY" 2>/dev/null)
    if [[ "$got" != "$want" ]]; then
      red "FAIL  $name — $filter expected '$want', got '$got'"; FAIL=$((FAIL+1)); return 1
    fi
  fi
  green "PASS  $name"; PASS=$((PASS+1))
}

notification_logs() {
  if [[ -n "$NOTIFICATION_LOG" ]]; then cat "$NOTIFICATION_LOG"
  else (cd "$ROOT_DIR" && docker compose logs --no-log-prefix notification-service 2>/dev/null); fi
}

# from_logs EMAIL REGEX -> newest match from the email logged for that address
from_logs() {
  local email=$1 regex=$2 v=""
  for _ in $(seq 1 20); do
    v=$(notification_logs | grep -A2 -F "to=$email" | grep -oP "$regex" | tail -1)
    [[ -n "$v" ]] && { echo "$v"; return 0; }
    sleep 1
  done
  return 1
}

get_otp() {
  local otp=""
  for _ in $(seq 1 20); do
    otp=$(notification_logs | grep -oP 'Your OTP is \K[0-9]{6}' | tail -1)
    [[ -n "$otp" ]] && { echo "$otp"; return 0; }
    sleep 1
  done
  return 1
}

wait_status() { # TX_ID WANTED TOKEN
  local id=$1 want=$2 token=$3 st=""
  for _ in $(seq 1 30); do
    call GET "/transactions/$id" "" "$token"; st=$(jq -r .status <<<"$BODY")
    [[ "$st" == "$want" ]] && return 0
    sleep 1
  done
  return 1
}

# signup NAME -> registers, verifies via emailed link, logs in; sets <NAME>_TOKEN, <NAME>_REFRESH, <NAME>_ID, <NAME>_EMAIL
signup() {
  local name=$1 email; email="$(tr '[:upper:]' '[:lower:]' <<<"$1").$RUN_ID@test.com"
  call POST /auth/register "{\"fullName\":\"$name Tester\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  check "POST /auth/register ($name)" 201 .emailVerified false
  local token; token=$(from_logs "$email" 'verify-email\?token=\K[A-Za-z0-9_-]+')
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$API/auth/verify-email?token=$token"); BODY=""
  check "GET  /auth/verify-email ($name, link from email)" 200
  call POST /auth/login "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  check "POST /auth/login ($name)" 200 .user.role USER
  printf -v "${name}_TOKEN" '%s' "$(jq -r .accessToken <<<"$BODY")"
  printf -v "${name}_REFRESH" '%s' "$(jq -r .refreshToken <<<"$BODY")"
  printf -v "${name}_ID" '%s' "$(jq -r .user.id <<<"$BODY")"
  printf -v "${name}_EMAIL" '%s' "$email"
}

echo "Testing $BASE_URL (run $RUN_ID)"
echo

# ================================================================ health
echo "== Health"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health"); BODY=""
check "GET  /actuator/health (public)" 200

# ================================================================ authentication
echo "== Auth — register / verify / login"
E="dave.$RUN_ID@test.com"
call POST /auth/register "{\"fullName\":\"Dave\",\"email\":\"$E\",\"password\":\"short\"}"
check "POST /auth/register weak password -> 400" 400
call POST /auth/register "{\"fullName\":\"Dave\",\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/register" 201
call POST /auth/register "{\"fullName\":\"Dave\",\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/register duplicate email -> 409" 409
call POST /auth/login "{\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/login before email verified -> 403" 403
call POST /auth/resend-verification "{\"email\":\"$E\"}"
check "POST /auth/resend-verification" 200
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$API/auth/verify-email?token=not-a-real-token"); BODY=""
check "GET  /auth/verify-email bad token -> 400" 400

signup Alice
signup Bob
signup Carol

call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"Wrong999\"}"
check "POST /auth/login wrong password -> 401" 401
call GET /auth/me "" "$Alice_TOKEN";              check "GET  /auth/me" 200 .email "$Alice_EMAIL"
call GET /auth/me;                                check "GET  /auth/me without token -> 401" 401
call GET /auth/me "" "eyJhbGciOiJIUzI1NiJ9.e30.bogus"; check "GET  /auth/me forged token -> 401" 401

echo "== Auth — refresh / logout"
OLD_REFRESH=$Alice_REFRESH
call POST /auth/refresh "{\"refreshToken\":\"$OLD_REFRESH\"}"
check "POST /auth/refresh" 200 '.accessToken | length > 20' true
Alice_TOKEN=$(jq -r .accessToken <<<"$BODY"); Alice_REFRESH=$(jq -r .refreshToken <<<"$BODY")
call POST /auth/refresh "{\"refreshToken\":\"$OLD_REFRESH\"}"
check "POST /auth/refresh reused token -> 401 (and all sessions revoked)" 401
call POST /auth/refresh "{\"refreshToken\":\"$Alice_REFRESH\"}"
check "     rotated token also revoked after reuse -> 401" 401
call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"$PASSWORD\"}"
Alice_TOKEN=$(jq -r .accessToken <<<"$BODY"); Alice_REFRESH=$(jq -r .refreshToken <<<"$BODY")
call POST /auth/logout "{\"refreshToken\":\"$Alice_REFRESH\"}"
check "POST /auth/logout" 200
call POST /auth/refresh "{\"refreshToken\":\"$Alice_REFRESH\"}"
check "POST /auth/refresh after logout -> 401" 401

echo "== Auth — admin login"
if [[ -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]]; then
  call POST /auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
  check "POST /auth/login (admin)" 200 .user.role ADMIN
  ADMIN_TOKEN=$(jq -r .accessToken <<<"$BODY"); ADMIN_ID=$(jq -r .user.id <<<"$BODY")
else
  red "FAIL  ADMIN_EMAIL / ADMIN_PASSWORD not found (env or .env)"; FAIL=$((FAIL+1)); ADMIN_TOKEN=""; ADMIN_ID=""
fi

# ================================================================ accounts
echo "== Accounts — ownership"
ACC='{"accountHolderName":"%s","phone":"%s","accountType":"%s","initialDeposit":%s}'
call POST /accounts/create "$(printf "$ACC" Alice "0$RANDOM$RUN_ID" SAVINGS 10000)"
check "POST /accounts/create without token -> 401" 401
call POST /accounts/create "$(printf "$ACC" Alice "1$RANDOM$RUN_ID" SAVINGS 10000)" "$Alice_TOKEN"
check "POST /accounts/create (Alice)" 201 .ownerId "$Alice_ID"; A=$(jq -r .accountNumber <<<"$BODY")
check "     account email = login email" 201 .email "$Alice_EMAIL"
call POST /accounts/create "$(printf "$ACC" Bob "2$RANDOM$RUN_ID" CURRENT 500)" "$Bob_TOKEN"
check "POST /accounts/create (Bob)" 201; B=$(jq -r .accountNumber <<<"$BODY")
call POST /accounts/create "$(printf "$ACC" Carol "3$RANDOM$RUN_ID" SAVINGS 2000)" "$Carol_TOKEN"
check "POST /accounts/create (Carol)" 201; C=$(jq -r .accountNumber <<<"$BODY")
call POST /accounts/create "$(printf "$ACC" Alice "4$RANDOM$RUN_ID" CURRENT 50)" "$Alice_TOKEN"
check "POST /accounts/create second account same email -> 409" 409
call POST /accounts/create '{}' "$Alice_TOKEN";            check "POST /accounts/create invalid body -> 400" 400

call GET "/accounts/$A" "" "$Alice_TOKEN";                 check "GET  /accounts/{n} own" 200 .accountHolderName Alice
call GET "/accounts/$A/balance" "" "$Alice_TOKEN";         check "GET  /accounts/{n}/balance own" 200 ".==10000" true
call GET "/accounts/$B" "" "$Alice_TOKEN";                 check "GET  /accounts/{n} someone else's -> 403" 403
call GET "/accounts/$B/balance" "" "$Alice_TOKEN";         check "GET  /accounts/{n}/balance someone else's -> 403" 403
call GET "/accounts/$B" "" "$ADMIN_TOKEN";                 check "GET  /accounts/{n} any account as ADMIN" 200
call GET "/accounts/me" "" "$Alice_TOKEN";                 check "GET  /accounts/me" 200 'length' 1
call GET "/accounts" "" "$Alice_TOKEN";                    check "GET  /accounts (all) as USER -> 403" 403
call GET "/accounts" "" "$ADMIN_TOKEN";                    check "GET  /accounts (all) as ADMIN" 200 'length > 2' true
call GET "/accounts" "" "$Alice_TOKEN" "X-User-Role: ADMIN"
check "GET  /accounts with spoofed X-User-Role header -> 403" 403
call GET "/accounts/$B" "" "$Alice_TOKEN" "X-User-Id: $Bob_ID"
check "GET  /accounts/{n} with spoofed X-User-Id header -> 403" 403
call GET "/accounts/0000000000" "" "$ADMIN_TOKEN";         check "GET  /accounts/{n} unknown -> 404" 404

echo "== Accounts — admin operations"
call PUT "/accounts/$B/credit?amount=100" "" "$Alice_TOKEN";  check "PUT  /accounts/{n}/credit as USER -> 403" 403
call PUT "/accounts/$B/credit?amount=100" "" "$ADMIN_TOKEN";  check "PUT  /accounts/{n}/credit as ADMIN" 200
call PUT "/accounts/$B/deduct?amount=100" "" "$ADMIN_TOKEN";  check "PUT  /accounts/{n}/deduct as ADMIN" 200
call PUT "/accounts/$B/deduct?amount=999999" "" "$ADMIN_TOKEN"; check "PUT  /accounts/{n}/deduct insufficient -> 422" 422
call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     balance unchanged after credit+deduct" 200 ".==500" true

# ================================================================ transactions
echo "== Transactions — normal transfer"
TX='{"senderAccountNumber":"%s","receiverAccountNumber":"%s","amount":%s,"type":"TRANSFER","description":"%s"}'
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)"
check "POST /transactions without token -> 401" 401
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 steal)" "$Bob_TOKEN"
check "POST /transactions from someone else's account -> 403" 403
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$ADMIN_TOKEN"
check "POST /transactions from a customer's account as ADMIN -> 403" 403
call POST /transactions "$(printf "$TX" "$A" "$A" 10 self)" "$Alice_TOKEN"
check "POST /transactions to the same account -> 400" 400
call POST /transactions "$(printf "$TX" "$B" "$A" 999999 big)" "$Bob_TOKEN"
check "POST /transactions insufficient funds -> 422" 422 '.detail | startswith("Insufficient funds")' true
call POST /transactions '{"amount":-5}' "$Alice_TOKEN";       check "POST /transactions invalid body -> 400" 400
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$Alice_TOKEN"
check "POST /transactions" 201 .status PROCESSING; T1=$(jq -r .id <<<"$BODY")
wait_status "$T1" COMPLETED "$Alice_TOKEN"; check "     saga: fraud check clean -> COMPLETED" 200 .status COMPLETED
call GET "/transactions/$T1" "" "$Alice_TOKEN";               check "GET  /transactions/{id} own" 200 .id "$T1"
call GET "/transactions/$T1" "" "$Bob_TOKEN";                 check "GET  /transactions/{id} someone else's -> 403" 403
call GET "/transactions/$T1" "" "$ADMIN_TOKEN";               check "GET  /transactions/{id} as ADMIN" 200
call GET "/transactions/does-not-exist" "" "$Alice_TOKEN";    check "GET  /transactions/{id} unknown -> 404" 404
call GET "/transactions/account/$A" "" "$Alice_TOKEN";        check "GET  /transactions/account/{n} own" 200 'length' 1
call GET "/transactions/account/$A" "" "$Bob_TOKEN";          check "GET  /transactions/account/{n} someone else's -> 403" 403
call GET "/transactions" "" "$Alice_TOKEN";                   check "GET  /transactions (all) as USER -> 403" 403
call GET "/transactions" "" "$ADMIN_TOKEN";                   check "GET  /transactions (all) as ADMIN" 200 'length > 0' true
sleep 1
call GET "/accounts/$A/balance" "" "$Alice_TOKEN";            check "     sender debited" 200 ".==9000" true
call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     receiver credited" 200 ".==1500" true

echo "== Transactions — suspicious transfer + OTP"
call POST /transactions "$(printf "$TX" "$A" "$B" 8500 large)" "$Alice_TOKEN"
check "POST /transactions (95% of balance)" 201; T2=$(jq -r .id <<<"$BODY")
wait_status "$T2" PENDING_VERIFICATION "$Alice_TOKEN"; check "     flagged -> PENDING_VERIFICATION" 200 .status PENDING_VERIFICATION
if OTP=$(get_otp); then
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Bob_TOKEN"; check "POST /transactions/{id}/verify by another user -> 403" 403
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Alice_TOKEN"; check "POST /transactions/{id}/verify (correct OTP)" 200 .status COMPLETED
  sleep 1
  call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     receiver credited" 200 ".==10000" true
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Alice_TOKEN"; check "POST /transactions/{id}/verify twice -> 409" 409
else
  yellow "SKIP  OTP verify — could not read OTP from notification logs"; SKIP=$((SKIP+1))
fi

call POST /transactions "$(printf "$TX" "$C" "$B" 1950 large)" "$Carol_TOKEN"
check "POST /transactions (Carol, 97% of balance)" 201; T3=$(jq -r .id <<<"$BODY")
if wait_status "$T3" PENDING_VERIFICATION "$Carol_TOKEN"; then
  call POST "/transactions/$T3/verify?otp=000000" "" "$Carol_TOKEN"; check "POST /transactions/{id}/verify (wrong OTP) -> FLAGGED" 200 .status FLAGGED
  sleep 2
  call GET "/accounts/$C" "" "$Carol_TOKEN";                  check "     sender account BLOCKED" 200 .status BLOCKED
  call GET "/accounts/$C/balance" "" "$Carol_TOKEN";          check "     sender refunded" 200 ".==2000" true
else
  check "     flagged -> PENDING_VERIFICATION" 200 .status PENDING_VERIFICATION
fi

echo "== Accounts — block"
call PUT "/accounts/$B/block" "" "$Alice_TOKEN";   check "PUT  /accounts/{n}/block as USER -> 403" 403
call PUT "/accounts/$B/block" "" "$ADMIN_TOKEN";   check "PUT  /accounts/{n}/block as ADMIN" 200
call GET "/accounts/$B" "" "$Bob_TOKEN";           check "     account status BLOCKED" 200 .status BLOCKED

# ================================================================ payments
echo "== Payments"
PAY='{"accountNumber":"%s","amount":25.50,"description":"top-up"}'
call POST /payments/create-order "$(printf "$PAY" "$A")";                check "POST /payments/create-order without token -> 401" 401
call POST /payments/create-order "$(printf "$PAY" "$B")" "$Alice_TOKEN"; check "POST /payments/create-order for someone else's account -> 403" 403
call POST /payments/create-order "$(printf "$PAY" "0000000000")" "$Alice_TOKEN"; check "POST /payments/create-order unknown account -> 404" 404
call POST /payments/create-order "$(printf "$PAY" "$A")" "$Alice_TOKEN"
if [[ "$STATUS" == 201 ]]; then check "POST /payments/create-order (Stripe)" 201 .status CREATED
else yellow "SKIP  POST /payments/create-order — HTTP $STATUS (check STRIPE_SECRET_KEY in .env)"; SKIP=$((SKIP+1)); fi
call POST /payments/create-order '{}' "$Alice_TOKEN";                    check "POST /payments/create-order invalid body -> 400" 400
call POST /payments/webhook '{}' "" "Stripe-Signature: t=1,v1=bad";      check "POST /payments/webhook (public) bad signature -> 400" 400

# ================================================================ password reset / change
echo "== Auth — forgot / reset / change password"
call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"$PASSWORD\"}"; Alice_REFRESH=$(jq -r .refreshToken <<<"$BODY")
call POST /auth/forgot-password '{"email":"nobody-'$RUN_ID'@test.com"}';  check "POST /auth/forgot-password unknown email -> 200 (no enumeration)" 200
call POST /auth/forgot-password "{\"email\":\"$Alice_EMAIL\"}";            check "POST /auth/forgot-password" 200
if RESET=$(from_logs "$Alice_EMAIL" 'Password reset token: \K[A-Za-z0-9_-]+'); then
  call POST /auth/reset-password "{\"token\":\"$RESET\",\"newPassword\":\"weak\"}"; check "POST /auth/reset-password weak password -> 400" 400
  call POST /auth/reset-password "{\"token\":\"$RESET\",\"newPassword\":\"NewSecret456\"}"; check "POST /auth/reset-password" 200
  call POST /auth/reset-password "{\"token\":\"$RESET\",\"newPassword\":\"Other789xyz\"}"; check "POST /auth/reset-password token reused -> 400" 400
  call POST /auth/refresh "{\"refreshToken\":\"$Alice_REFRESH\"}";                  check "     old sessions revoked after reset -> 401" 401
  call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"$PASSWORD\"}";  check "POST /auth/login old password -> 401" 401
  call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"NewSecret456\"}"; check "POST /auth/login new password" 200
  Alice_TOKEN=$(jq -r .accessToken <<<"$BODY")
  call POST /auth/change-password '{"currentPassword":"WrongPass1","newPassword":"Another789"}' "$Alice_TOKEN"
  check "POST /auth/change-password wrong current -> 400" 400
  call POST /auth/change-password '{"currentPassword":"NewSecret456","newPassword":"Another789"}' "$Alice_TOKEN"
  check "POST /auth/change-password" 200
  call POST /auth/login "{\"email\":\"$Alice_EMAIL\",\"password\":\"Another789\"}"; check "POST /auth/login after change" 200
else
  yellow "SKIP  password reset — could not read reset token from notification logs"; SKIP=$((SKIP+1))
fi

# ================================================================ admin user management
echo "== Admin — user management"
call GET /users "" "$Alice_TOKEN";                  check "GET  /users as USER -> 403" 403
call GET /users "" "$ADMIN_TOKEN";                  check "GET  /users as ADMIN" 200 'length > 3' true
call GET "/users/$Bob_ID" "" "$ADMIN_TOKEN";        check "GET  /users/{id}" 200 .email "$Bob_EMAIL"
call PATCH "/users/$Bob_ID" '{"enabled":false}' "$ADMIN_TOKEN"; check "PATCH /users/{id} disable" 200 .enabled false
call POST /auth/login "{\"email\":\"$Bob_EMAIL\",\"password\":\"$PASSWORD\"}"; check "     disabled user cannot log in -> 403" 403
call PATCH "/users/$Bob_ID" '{"enabled":true}' "$ADMIN_TOKEN";  check "PATCH /users/{id} enable" 200 .enabled true
call PATCH "/users/$Bob_ID" '{"role":"ADMIN"}' "$ADMIN_TOKEN";  check "PATCH /users/{id} promote to ADMIN" 200 .role ADMIN
call POST /auth/login "{\"email\":\"$Bob_EMAIL\",\"password\":\"$PASSWORD\"}"; BOB_ADMIN=$(jq -r .accessToken <<<"$BODY")
call GET /users "" "$BOB_ADMIN";                    check "     promoted user can use admin endpoints" 200
call PATCH "/users/$Bob_ID" '{"role":"USER"}' "$ADMIN_TOKEN";   check "PATCH /users/{id} demote to USER" 200 .role USER
call PATCH "/users/$ADMIN_ID" '{"role":"USER"}' "$ADMIN_TOKEN"; check "PATCH /users/{id} admin demoting self -> 409" 409

echo
echo "Result: $PASS passed, $FAIL failed, $SKIP skipped"
[[ $FAIL -eq 0 ]]
