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
TRACE_FILE="${TRACE_FILE:-}"
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
  else (cd "$ROOT_DIR" && docker compose logs --no-log-prefix --timestamps notification-service 2>/dev/null); fi
}

# log_mark -> how many lines the notification log has now. Take a mark before an action, then read only what comes after it,
# so a message from an earlier step can never be mistaken for the one this step caused.
log_mark() { notification_logs | wc -l; }

# link_after MARK EMAIL REGEX -> the newest match logged for EMAIL after line MARK. Waits up to 30 s, then fails.
link_after() {
  local mark=$1 email=$2 regex=$3 v=""
  for _ in $(seq 1 30); do
    v=$(notification_logs | tail -n "+$((mark + 1))" | grep -A2 -F "to=$email" | grep -oP "$regex" | tail -1)
    [[ -n "$v" ]] && { echo "$v"; return 0; }
    sleep 1
  done
  return 1
}

# Evidence for a 400 from verify-email (wallet-plan 8.29). When TRACE_FILE is set, each verify call appends one row: whether the
# token sent was empty and its length, the SHA-256 of the token (it matches auth_tokens.token_hash; the raw token is never
# written), the status and body, and three times: the request that sent the email, the email's log line, the verify call.
now_ts() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

# logged_ts_after MARK EMAIL -> the time of the first "to=EMAIL" line logged after line MARK (the log carries timestamps).
logged_ts_after() {
  notification_logs | tail -n "+$(($1 + 1))" | grep -F "to=$2" | sed -n '1p' | awk '{print $1}'
}

# verify_call CALL TOKEN SENT LOGGED MARK EMAIL -> calls verify-email, sets STATUS and BODY for check, and traces the call.
verify_call() {
  local call=$1 token=$2 sent=$3 logged=$4 mark=$5 email=$6 verified resp sha="" empty=no to_lines="" link_lines=""
  verified=$(now_ts)
  resp=$(curl -s -w $'\n%{http_code}' "$API/auth/verify-email?token=$token")
  STATUS=${resp##*$'\n'}; BODY=${resp%$'\n'*}
  [[ -n "$TRACE_FILE" ]] || return 0
  if [[ -z "$token" ]]; then empty=yes; else sha=$(printf '%s' "$token" | sha256sum | cut -d' ' -f1); fi
  if [[ -n "$mark" ]]; then
    to_lines=$(notification_logs | tail -n "+$((mark + 1))" | grep -cF "to=$email")
    link_lines=$(notification_logs | tail -n "+$((mark + 1))" | grep -cF 'verify-email?token=')
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$call" "$empty" "${#token}" "$sha" "$STATUS" \
    "$(tr '\t\r\n' '   ' <<<"$BODY" | cut -c1-200)" "$sent" "$logged" "$verified" "$to_lines" "$link_lines" >> "$TRACE_FILE"
}

# otp_after MARK -> the newest OTP logged after line MARK. Waits up to 30 s, then fails.
otp_after() {
  local mark=$1 otp=""
  for _ in $(seq 1 30); do
    otp=$(notification_logs | tail -n "+$((mark + 1))" | grep -oP 'Your OTP is \K[0-9]{6}' | tail -1)
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
  local mark; mark=$(log_mark)
  local sent; sent=$(now_ts)
  call POST /auth/register "{\"fullName\":\"$name Tester\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  check "POST /auth/register ($name)" 201 .emailVerified false
  local token; token=$(link_after "$mark" "$email" 'verify-email\?token=\K[A-Za-z0-9_-]+')
  verify_call "$name (link from email)" "$token" "$sent" "$(logged_ts_after "$mark" "$email")" "$mark" "$email"
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
dave_mark=$(log_mark)
call POST /auth/register "{\"fullName\":\"Dave\",\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/register" 201
# Wait for the first email before the resend starts. The notification log can lag the register by several seconds. If the
# first email arrived after the resend's mark, the resend's lookup would take its superseded link, and verify would reject it.
link_after "$dave_mark" "$E" 'verify-email\?token=\K[A-Za-z0-9_-]+' > /dev/null \
  || { red "FAIL  Dave's first verification email never reached the log"; FAIL=$((FAIL+1)); }
call POST /auth/register "{\"fullName\":\"Dave\",\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/register duplicate email -> 409" 409
call POST /auth/login "{\"email\":\"$E\",\"password\":\"$PASSWORD\"}"
check "POST /auth/login before email verified -> 403" 403
resend_mark=$(log_mark)
resend_sent=$(now_ts)
call POST /auth/resend-verification "{\"email\":\"$E\"}"
check "POST /auth/resend-verification" 200
resend_token=$(link_after "$resend_mark" "$E" 'verify-email\?token=\K[A-Za-z0-9_-]+')
verify_call "Dave, link from the resend" "$resend_token" "$resend_sent" "$(logged_ts_after "$resend_mark" "$E")" "$resend_mark" "$E"
check "GET  /auth/verify-email (Dave, link from the resend)" 200
verify_call "bad token" "not-a-real-token" "" "" "" ""
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
call GET "/accounts/$A/balance" "" "$Alice_TOKEN";         check "GET  /accounts/{n}/balance own" 200 '.=="10000.00"' true
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
call PUT "/accounts/$B/credit?amount=100" "" "$Alice_TOKEN";  check "PUT  /accounts/{n}/credit as USER -> 403 (gateway admin rule)" 403
call PUT "/accounts/$B/credit?amount=100" "" "$ADMIN_TOKEN";  check "PUT  /accounts/{n}/credit (removed in 3c.3) as ADMIN -> 404" 404
call PUT "/admin/accounts/$B/credit?amount=100&reason=smoke" "" "$ADMIN_TOKEN" "Idempotency-Key: smoke-credit-$RUN_ID"; check "PUT  /admin/accounts/{n}/credit as ADMIN" 201
call PUT "/admin/accounts/$B/deduct?amount=100&reason=smoke" "" "$ADMIN_TOKEN" "Idempotency-Key: smoke-deduct-$RUN_ID"; check "PUT  /admin/accounts/{n}/deduct as ADMIN" 201
call PUT "/admin/accounts/$B/deduct?amount=999999&reason=smoke" "" "$ADMIN_TOKEN" "Idempotency-Key: smoke-overdraw-$RUN_ID"; check "PUT  /admin/accounts/{n}/deduct insufficient -> 422" 422
call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     balance unchanged after credit+deduct" 200 '.=="500.00"' true

# ================================================================ transfer PIN (wallet-plan 3.5, D2)
echo "== Wallet — transfer PIN"
# set_pin NAME -> asks for a code, reads it from the notification log, and sets the PIN to 1234.
set_pin() {
  local name=$1 token_var="${1}_TOKEN" token
  token=${!token_var}
  local mark; mark=$(log_mark)
  call POST /wallet/pin/otp "" "$token"
  check "POST /wallet/pin/otp ($name)" 200 .sent true
  local code
  if code=$(otp_after "$mark"); then
    call PUT /wallet/pin "{\"code\":\"$code\",\"password\":\"$PASSWORD\",\"pin\":\"1234\"}" "$token"
    check "PUT  /wallet/pin ($name, first PIN)" 200 .status PIN_SET
  else
    red "SKIP  PUT /wallet/pin ($name) — could not read the code from notification logs"; SKIP=$((SKIP+1))
  fi
}
set_pin Alice; set_pin Bob; set_pin Carol
call POST /wallet/pin/otp "" "$Alice_TOKEN"; check "POST /wallet/pin/otp again within a minute -> 429" 429

# ================================================================ transactions
echo "== Transactions — normal transfer"
TX='{"senderAccountNumber":"%s","receiverAccountNumber":"%s","amount":%s,"type":"TRANSFER","description":"%s","pin":"1234"}'
# wallet-plan 3.6: every transfer carries a fresh Idempotency-Key, unless the check is about the key itself.
idem() { echo "smoke-$RUN_ID-$RANDOM$RANDOM$RANDOM"; }
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "" "Idempotency-Key: $(idem)"
check "POST /transactions without token -> 401" 401
call POST /transactions "$(printf '{"senderAccountNumber":"%s","receiverAccountNumber":"%s","amount":10,"type":"TRANSFER","description":"no pin"}' "$A" "$B")" "$Alice_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions without a PIN -> 400" 400
call POST /transactions "$(printf '{"senderAccountNumber":"%s","receiverAccountNumber":"%s","amount":10,"type":"TRANSFER","description":"wrong pin","pin":"9999"}' "$A" "$B")" "$Alice_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions with a wrong PIN -> 403" 403 .detail "Wrong PIN"
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 steal)" "$Bob_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions from someone else's account -> 403" 403
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$ADMIN_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions from a customer's account as ADMIN -> 403" 403
call POST /transactions "$(printf "$TX" "$A" "$A" 10 self)" "$Alice_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions to the same account -> 400" 400
call POST /transactions "$(printf "$TX" "$B" "$A" 999999 big)" "$Bob_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions insufficient funds -> 422" 422 '.detail | startswith("Insufficient funds")' true
call POST /transactions '{"amount":-5}' "$Alice_TOKEN" "Idempotency-Key: $(idem)";       check "POST /transactions invalid body -> 400" 400
K1=$(idem)
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$Alice_TOKEN" "Idempotency-Key: $K1"
check "POST /transactions" 201 .status PROCESSING; T1=$(jq -r .id <<<"$BODY")
# A repeat with the same key and body returns the same transfer. A different body with the same key is refused. No key is refused.
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$Alice_TOKEN" "Idempotency-Key: $K1"
check "POST /transactions repeated with the same key -> the same transfer" 201 .id "$T1"
call POST /transactions "$(printf "$TX" "$A" "$B" 1001 rent)" "$Alice_TOKEN" "Idempotency-Key: $K1"
check "POST /transactions same key, different body -> 422" 422
call POST /transactions "$(printf "$TX" "$A" "$B" 1000 rent)" "$Alice_TOKEN"
check "POST /transactions without Idempotency-Key -> 400" 400
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
call GET "/accounts/$A/balance" "" "$Alice_TOKEN";            check "     sender debited" 200 '.=="9000.00"' true
call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     receiver credited" 200 '.=="1500.00"' true

echo "== Transactions — suspicious transfer + OTP"
otp_mark=$(log_mark)
call POST /transactions "$(printf "$TX" "$A" "$B" 8500 large)" "$Alice_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions (95% of balance)" 201; T2=$(jq -r .id <<<"$BODY")
check "     transfer amount is the string \"8500.00\"" 201 '.amount=="8500.00"' true
wait_status "$T2" PENDING_VERIFICATION "$Alice_TOKEN"; check "     flagged -> PENDING_VERIFICATION" 200 .status PENDING_VERIFICATION
if OTP=$(otp_after "$otp_mark"); then
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Bob_TOKEN"; check "POST /transactions/{id}/verify by another user -> 403" 403
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Alice_TOKEN"; check "POST /transactions/{id}/verify (correct OTP)" 200 .status COMPLETED
  sleep 1
  call GET "/accounts/$B/balance" "" "$Bob_TOKEN";              check "     receiver credited" 200 '.=="10000.00"' true
  call POST "/transactions/$T2/verify?otp=$OTP" "" "$Alice_TOKEN"; check "POST /transactions/{id}/verify twice -> 409" 409
else
  yellow "SKIP  OTP verify — could not read OTP from notification logs"; SKIP=$((SKIP+1))
fi

call POST /transactions "$(printf "$TX" "$C" "$B" 1950 large)" "$Carol_TOKEN" "Idempotency-Key: $(idem)"
check "POST /transactions (Carol, 97% of balance)" 201; T3=$(jq -r .id <<<"$BODY")
if wait_status "$T3" PENDING_VERIFICATION "$Carol_TOKEN"; then
  # wallet-plan D6: the first two wrong OTPs get a 400 and the transfer waits; the third flags it, refunds it and blocks the account.
  call POST "/transactions/$T3/verify?otp=000000" "" "$Carol_TOKEN"; check "POST /transactions/{id}/verify (wrong OTP 1 of 3) -> 400" 400 .detail "Incorrect OTP. 2 attempts left"
  call POST "/transactions/$T3/verify?otp=000000" "" "$Carol_TOKEN"; check "POST /transactions/{id}/verify (wrong OTP 2 of 3) -> 400" 400 .detail "Incorrect OTP. 1 attempt left"
  call POST "/transactions/$T3/verify?otp=000000" "" "$Carol_TOKEN"; check "POST /transactions/{id}/verify (wrong OTP 3 of 3) -> FLAGGED" 200 .status FLAGGED
  sleep 2
  call GET "/accounts/$C" "" "$Carol_TOKEN";                  check "     sender account BLOCKED" 200 .status BLOCKED
  call GET "/accounts/$C/balance" "" "$Carol_TOKEN";          check "     sender refunded" 200 '.=="2000.00"' true
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
reset_mark=$(log_mark)
call POST /auth/forgot-password "{\"email\":\"$Alice_EMAIL\"}";            check "POST /auth/forgot-password" 200
if RESET=$(link_after "$reset_mark" "$Alice_EMAIL" 'Password reset token: \K[A-Za-z0-9_-]+'); then
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

# ================================================================ card deposits (wallet-plan 3.5, phase 5)
# Last, because each deposit moves money. Carol's account is used: the password steps above revoke Alice's sessions, and her
# first account is blocked by the transfer checks. Balances are read as a change from the start, because earlier checks moved
# Carol's balance. The Stripe webhook is signed here with openssl, using STRIPE_WEBHOOK_SECRET from .env, which is never printed.
echo "== Wallet — card deposits"
STRIPE_WH_SECRET="${STRIPE_WEBHOOK_SECRET:-$(env_value STRIPE_WEBHOOK_SECRET)}"
DEP_ACC=$C
call GET "/accounts/$DEP_ACC/balance" "" "$Carol_TOKEN"; check "GET /accounts/{n}/balance (Carol, before the deposits)" 200
BAL0=$(jq -r . <<<"$BODY")

# open_deposit KEY AMOUNT: opens a deposit into Carol's account. The response is left in BODY, and the deposit id in DEP.
open_deposit() {
  call POST /wallet/deposits "{\"accountNumber\":\"$DEP_ACC\",\"amount\":\"$2\"}" "$Carol_TOKEN" "Idempotency-Key: $1"
  DEP=$(jq -r .depositId <<<"$BODY" 2>/dev/null)
}
# hook_event TYPE EVENT_ID DEPOSIT_ID AMOUNT_KOBO RECEIVED_KOBO CURRENCY [FAILURE_REASON]: a Stripe event as Stripe sends it.
hook_event() {
  local type=$1 evt=$2 dep=$3 amount=$4 received=$5 cur=$6 reason=${7:-} err=""
  [[ -n "$reason" ]] && err=",\"last_payment_error\":{\"message\":\"$reason\"}"
  printf '{"id":"%s","object":"event","api_version":"2025-05-28.basil","type":"%s","data":{"object":{"id":"pi_smoke_%s","object":"payment_intent","amount":%s,"amount_received":%s,"currency":"%s"%s,"metadata":{"depositId":"%s"}}}}' \
    "$evt" "$type" "$RANDOM$RANDOM" "$amount" "$received" "$cur" "$err" "$dep"
}
# signed_webhook SECRET PAYLOAD: posts the event with Stripe's signature, t=<unix>,v1=HMAC-SHA256(secret, "<t>.<payload>").
signed_webhook() {
  local secret=$1 payload=$2 ts sig out
  ts=$(date +%s)
  sig=$(printf '%s.%s' "$ts" "$payload" | openssl dgst -sha256 -hmac "$secret" -hex | awk '{print $NF}')
  out=$(curl -s -w $'\n%{http_code}' -X POST "$API/payments/webhook" -H 'Content-Type: application/json' \
        -H "Stripe-Signature: t=$ts,v1=$sig" --data-binary "$payload")
  STATUS=$(tail -n1 <<<"$out"); BODY=$(sed '$d' <<<"$out")
}
# expect_deposit NAME ID STATUS: polls the deposit, as its owner, for up to 30 seconds until it has that status.
expect_deposit() {
  local i
  for i in $(seq 1 30); do
    call GET "/wallet/deposits/$2" "" "$Carol_TOKEN"
    if [[ "$(jq -r .status <<<"$BODY" 2>/dev/null)" == "$3" ]]; then green "PASS  $1"; PASS=$((PASS+1)); return 0; fi
    sleep 1
  done
  red "FAIL  $1 — deposit $2 did not reach $3: ${BODY:0:200}"; FAIL=$((FAIL+1))
}
# expect_balance NAME DELTA: the balance is the starting balance plus DELTA, as a money string with two decimals.
expect_balance() {
  call GET "/accounts/$DEP_ACC/balance" "" "$Carol_TOKEN"
  local got want
  got=$(jq -r . <<<"$BODY" 2>/dev/null)
  want=$(awk -v b="$BAL0" -v d="$2" 'BEGIN { printf "%.2f", b + d }')
  if [[ "$got" == "$want" ]]; then green "PASS  $1 ($got)"; PASS=$((PASS+1)); else red "FAIL  $1 — balance $got, expected $want"; FAIL=$((FAIL+1)); fi
}

open_deposit "smoke-dep-$RUN_ID-ok" 20000.00; DEP_OK=$DEP
check "POST /wallet/deposits 20000.00 -> 201 PENDING with a card intent" 201 .status PENDING
check "     the intent is a Stripe payment intent" 201 '.paymentIntentId | startswith("pi_")' true
call POST /wallet/deposits "{\"accountNumber\":\"$DEP_ACC\",\"amount\":\"20000.00\"}" "$Carol_TOKEN" "Idempotency-Key: smoke-dep-$RUN_ID-ok"
check "     the same key replays the same deposit" 201 .depositId "$DEP_OK"
call POST /wallet/deposits "{\"accountNumber\":\"$DEP_ACC\",\"amount\":\"20001.00\"}" "$Carol_TOKEN" "Idempotency-Key: smoke-dep-$RUN_ID-ok"
check "     the same key with another amount -> 422" 422
call POST /wallet/deposits "{\"accountNumber\":\"$DEP_ACC\",\"amount\":\"19999.99\"}" "$Carol_TOKEN" "Idempotency-Key: smoke-dep-$RUN_ID-low"
check "     below CARD_DEPOSIT_MIN -> 400" 400
call POST /wallet/deposits "{\"accountNumber\":\"$DEP_ACC\",\"amount\":\"20000.00\"}" "$Alice_TOKEN" "Idempotency-Key: smoke-dep-$RUN_ID-alice"
check "     another user's account -> 403, for the ownership reason" 403 .detail "This account belongs to another user"

# 1. The full amount arrives: the deposit completes and Carol's account is credited once, with the amount requested.
EVT_OK="evt_smoke_${RUN_ID}_ok"
PAYLOAD_OK=$(hook_event payment_intent.succeeded "$EVT_OK" "$DEP_OK" 2000000 2000000 ngn)
signed_webhook "$STRIPE_WH_SECRET" "$PAYLOAD_OK"; check "POST /payments/webhook, signed, full amount -> 200" 200
expect_deposit "the deposit completes after the webhook" "$DEP_OK" COMPLETED
expect_balance "the account is credited 20000.00" 20000.00
# 2. The same Stripe event again: applied once, so nothing moves.
signed_webhook "$STRIPE_WH_SECRET" "$PAYLOAD_OK"; check "POST /payments/webhook, the same event again -> 200" 200
sleep 3; expect_balance "a replayed event credits nothing more" 20000.00

# 3. A webhook with a wrong signature is refused, and changes nothing.
open_deposit "smoke-dep-$RUN_ID-forged" 20000.00; DEP_FORGED=$DEP
PAYLOAD_FORGED=$(hook_event payment_intent.succeeded "evt_smoke_${RUN_ID}_forged" "$DEP_FORGED" 2000000 2000000 ngn)
signed_webhook "whsec_not_the_real_secret" "$PAYLOAD_FORGED"; check "POST /payments/webhook with a wrong signature -> 400" 400
sleep 3; call GET "/wallet/deposits/$DEP_FORGED" "" "$Carol_TOKEN"; check "     the forged event leaves the deposit PENDING" 200 .status PENDING
expect_balance "     and the account is unchanged" 20000.00

# 4. A shortfall: 19000.00 arrives for 20000.00. Nothing is credited, the deposit fails, and the receipt goes to suspense (D26).
open_deposit "smoke-dep-$RUN_ID-short" 20000.00; DEP_SHORT=$DEP
signed_webhook "$STRIPE_WH_SECRET" "$(hook_event payment_intent.succeeded "evt_smoke_${RUN_ID}_short" "$DEP_SHORT" 2000000 1900000 ngn)"
check "POST /payments/webhook, a shortfall -> 200" 200
expect_deposit "a shortfall fails the deposit" "$DEP_SHORT" FAILED
call GET "/wallet/deposits/$DEP_SHORT" "" "$Carol_TOKEN"; check "     the failure says what arrived" 200 '.failureReason | test("less than")' true
expect_balance "     and nothing is credited for it" 20000.00

# 5. A surplus: 20500.00 arrives for 20000.00. The deposit completes with the requested 20000.00, and the surplus goes to suspense.
open_deposit "smoke-dep-$RUN_ID-surplus" 20000.00; DEP_SURPLUS=$DEP
signed_webhook "$STRIPE_WH_SECRET" "$(hook_event payment_intent.succeeded "evt_smoke_${RUN_ID}_surplus" "$DEP_SURPLUS" 2000000 2050000 ngn)"
check "POST /payments/webhook, a surplus -> 200" 200
expect_deposit "a surplus completes the deposit" "$DEP_SURPLUS" COMPLETED
expect_balance "     and the account gets the requested 20000.00 only (40000.00 in all)" 40000.00

# 6. The payment fails at Stripe: the deposit fails with Stripe's reason, and nothing is credited.
open_deposit "smoke-dep-$RUN_ID-declined" 20000.00; DEP_DECLINED=$DEP
signed_webhook "$STRIPE_WH_SECRET" "$(hook_event payment_intent.payment_failed "evt_smoke_${RUN_ID}_declined" "$DEP_DECLINED" 2000000 0 ngn "Card declined")"
check "POST /payments/webhook, a failed payment -> 200" 200
expect_deposit "a failed payment fails the deposit" "$DEP_DECLINED" FAILED
call GET "/wallet/deposits/$DEP_DECLINED" "" "$Carol_TOKEN"; check "     the failure carries Stripe's reason" 200 .failureReason "Card declined"
expect_balance "     and the account is unchanged" 40000.00

echo
echo "Result: $PASS passed, $FAIL failed, $SKIP skipped"
[[ $FAIL -eq 0 ]]
