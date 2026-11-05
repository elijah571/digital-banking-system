# Threat model: the transfer and wallet PIN

Status: written 2026-11-05, first step of phase 4 (wallet-plan section 5). Read with wallet-plan 3.5 (PIN), 3.7 (security), 8.22 (the PIN gate) and 8.32 (the client waits for phase 4). Where this document and the plan disagree, the plan wins until the decision is changed in the plan.

## 1. What is protected

| Asset | Why it matters |
|---|---|
| The money in a wallet | A transfer, and later a withdrawal, moves it. The PIN is the second factor on those moves. |
| The PIN itself | A 4-digit space is 10,000 values. Anything that can test PINs quickly can find one. |
| The OTP for PIN changes | A six-digit code that authorises setting a new PIN. |
| The account password | Checked, never stored, by auth-service. Wallet only asks whether it is right. |
| The PIN pepper (`PIN_PEPPER`) | With the hash table, it is what stops an offline guess. |
| The OTP key (`PIN_OTP_HMAC_KEY`) | Keys the OTP hash. It is its own key, not the shared internal secret (lesson from the 3c.5 review). |

## 2. Who can act

| Actor | Can do | Cannot do |
|---|---|---|
| The account owner | Verify their PIN in a transfer. Set or change the PIN with the OTP and the password. | Read anyone else's PIN state. |
| Someone with a stolen access token | Call every user endpoint as that user, including the PIN endpoints. | Set a PIN without the password and the OTP, which go to the owner's email. |
| Someone with the password and the access token | Start a PIN change. | Complete it without the OTP. |
| Someone who also reads the owner's email | Complete a PIN change, then move money. | Be stopped by the PIN flow alone. See residual risk R1. |
| A calling service (SERVICE token) | Call only the endpoints its `sub` is allowed (CallerAllowList). | Call any other internal path. |
| An operator with database access | Read `pin_credential` hashes. | Crack them quickly without `PIN_PEPPER`. See R2. |
| An operator with the environment, or a read of the wallet process memory | Anything the wallet can do, including reading the pepper. | Be prevented by the design. Accepted: see R3. |

## 3. Flows

**Set, change and reset (one endpoint, the same checks).** The plan requires an OTP and the current password for all three.

1. `POST /api/v1/wallet/pin/otp`. Wallet makes a six-digit code and stores only `HMAC(PIN_OTP_HMAC_KEY, "pin-otp|" + userId + "|" + code)` in Redis, with a five-minute TTL. It publishes the code on `notification.otp` with `purpose=PIN_CHANGE`, and notification-service emails it.
2. `PUT /api/v1/wallet/pin` with `{otp, password, pin}`. Wallet checks the lockout first, then compares the OTP, then calls auth-service `POST /internal/v1/auth/verify-password`, then stores `Argon2id(HMAC-SHA256(PIN_PEPPER, userId ‖ pin))`. A wrong password counts toward the lockout (D-P3).
3. On success, wallet emails the owner that the PIN changed (D-P6).

**Verify (every transfer while `TRANSFER_PIN_REQUIRED` is on).** transaction-service calls `POST /internal/v1/pin/verify` with `{userId, pin}` and the SERVICE token. Wallet checks the lockout, then the hash. A wrong PIN increments the counter. A right PIN resets it.

## 4. Threats and mitigations

| # | Threat | Mitigation | Test that proves it |
|---|---|---|---|
| T1 | Online guessing of the PIN with many parallel requests | Each attempt is counted first, with an atomic update under the row lock (`SELECT … FOR UPDATE`, then `failed_count = failed_count + 1`). The lock is checked before the hash. Five failures lock the PIN for 30 minutes; each later lockout doubles, capped at 24 hours (D-P2). | 50 parallel wrong PINs give `failed_count` = 5 and a lock. The right PIN is refused while locked. |
| T2 | Guessing the OTP in parallel | INCR before the compare, as in 3c.5. Five tries per code. A decision claim (`SETNX`) makes single use. | Ten parallel wrong OTPs give exactly five counted attempts and one refusal after the lock. The same right OTP sent twice gives one success. |
| T3 | Offline guessing from a database dump | Argon2id, which is slow. HMAC with `PIN_PEPPER` before Argon2, so the dump alone is not enough. The userId is in the input, so equal PINs give different hashes. | A hash does not verify with the wrong pepper, and does not verify for another user's id. |
| T4 | PIN, OTP or password in logs or request traces | A Logback masking layer and a request-logging filter that mask `pin`, `otp`, `password` and `destination.accountNumber`. No code path logs them. | The flows run with known values, and the test asserts that none of them appears in captured logs. |
| T5 | Account takeover through a stolen access token | A PIN change needs the password and the OTP. The OTP goes to the owner's email. | A call with only the access token is refused. |
| T6 | Lockout used to block a victim (DoS) | Accepted, and bounded: the lock lasts at most 24 hours and affects transfers and PIN changes only. Read endpoints keep working. See R4. | A locked user can still read balances. |
| T7 | Password guessing through the PIN change endpoint | Wrong passwords count toward the same lockout (D-P3). auth-service also rate-limits `verify-password` per user (D-P10). | Five wrong passwords lock the PIN. |
| T8 | Forged internal calls to the PIN verify endpoint | The allow-list admits only transaction-service (`sub`), and the audience must be wallet. Other callers get 403. | A token from another service gets 403. A token for another audience gets 401. |
| T9 | Timing differences that reveal the PIN | The hash compare is constant-time (`MessageDigest.isEqual`). Argon2 runs on every attempt that passes the lock check, so a locked PIN and a wrong PIN take different paths, but a wrong PIN and a right PIN take the same path up to the compare. | Covered by the code review, not a timing test. |
| T10 | Replay of a PIN change | The OTP is single use, and the OTP claim is recorded. The password is checked again on each change. | The same OTP and password used twice: one change, one refusal. |
| T11 | Concurrent PIN changes | The `pin_credential` row is locked for the whole change. | Two parallel changes: one wins, and the lockout counter is not reset by the loser. |
| T12 | Pepper compromise | Pepper is read at startup from the environment. It must be at least 32 bytes, or the service does not start. `pepper_version` is stored so the pepper can be rotated later without a flag day. | The service refuses to start with a short or missing pepper. |
| T13 | Redis outage | OTP issue and verify fail closed (503), and so do `verify-password` rate limits (wallet-plan 3.10). PIN verify uses MySQL only, so transfers still have a PIN check during a Redis outage. | With Redis stopped: OTP issue gets 503. PIN verify still works. |
| T14 | An error message reveals whether a PIN is set | Responses for "no PIN set" and "wrong PIN" are different on purpose, because the transfer API needs the first to say "set a PIN first" (D2). Only the owner can call the endpoints, so this is not an enumeration risk. | A user cannot query another user's PIN state. |

## 5. Residual risks (accepted, and why)

- **R1. Email compromise.** Someone who has the password, the access token and the owner's email can change the PIN and then move money. The email notice (D-P6) tells the owner, but cannot stop the change. The fix is in the owner's email account, not in this service.
- **R2. A 4-digit PIN has 10,000 values.** An offline attacker with the dump and the pepper can test all of them. Argon2 makes each guess cost real time, but a determined attacker with the pepper can still get through. The pepper must be kept outside the database (secret store in production, see `docs/deploy.md` when it exists).
- **R3. A compromised wallet process.** It holds the pepper and the OTP key. Its trust is the same as any service that holds a secret. The mitigation is the allow-lists and the audit log, not the cryptography.
- **R4. Lockout as a denial of service.** An attacker who knows a victim's password can lock the victim's PIN for up to 24 hours. This is accepted (D-P2), because the alternative lets a PIN be guessed.
- **R5. Plain-text PIN on the internal hop.** transaction-service sends the PIN to wallet. In dev and in the compose network this is plain HTTP. Production must keep internal traffic on a private network, and must use TLS if it leaves one. See `docs/deploy.md` when it exists.

## 6. Decisions

Where the plan left a choice open, the plan's rule applies: the recommended option is used. Each one is listed here so it can be changed before the code is written.

| ID | Decision | Recommended option taken | Why |
|---|---|---|---|
| D-P1 | PIN length | **4 digits**, `^\d{4}$` | The usual length for a bank PIN. The lockout and the pepper do the work that length would otherwise do. Six digits is a one-line change if you prefer it. |
| D-P2 | Lockout | Five failures lock for 30 minutes. Each later lockout doubles, capped at 24 hours. A right PIN resets the failure count and the level. | The plan's rule (3.5). |
| D-P3 | A wrong password on set or change | Counts toward the same lockout as a wrong PIN. | Otherwise the password endpoint is a second, unlimited guess path. |
| D-P4 | Who makes the PIN OTP | Wallet makes it. It goes out on the existing `notification.otp` topic, with `purpose=PIN_CHANGE`. Six digits, five minutes, five tries, single use. Its own key, `PIN_OTP_HMAC_KEY`. | The plan says wallet owns the OTP. Reusing the topic avoids a new one (D6). |
| D-P5 | Set, change and reset | One endpoint, `PUT /api/v1/wallet/pin`, requiring the OTP and the password. The current PIN is not asked for. | The plan (3.5) says all three need the OTP and the password. Asking for the old PIN would stop a user who has forgotten it. |
| D-P6 | Email on a PIN change | Yes. The owner gets an email each time the PIN is set or changed. | It is the only signal the owner gets if R1 happens. |
| D-P7 | Pepper | `PIN_PEPPER` from the environment, at least 32 bytes, required at startup. `pepper_version` stored, rotation not built. | The plan's rule (3.5). Rotation can come later without changing the stored format. |
| D-P8 | Status codes | Wrong PIN gives 403. No PIN set gives 403 with "Set a PIN first". Locked gives 423 with `Retry-After`. | 403 for a wrong PIN is in the plan (6b, review fix 1). 423 tells the client to wait, not to retry. |
| D-P9 | Redis outage | OTP issue and verify give 503. PIN verify (MySQL) keeps working. | The plan's rule (3.10). |
| D-P10 | Rate limit on `verify-password` | 5 failures per user per 30 minutes, then 429, in Redis. Each call is audited. | The plan requires a rate limit. These numbers match the PIN lockout's first step. |

## 7. Lessons carried in from the 3c.5 and 3c.7 reviews

- Count an attempt before comparing it, with an atomic increment. Never read, compare, then write.
- Make the outcome of a code a single decision: one claim, and every other request is refused.
- Key each HMAC with its own key. Do not share a secret across services.
- Fail closed where a missing check would let money move.
- Never print a secret, and never store one in a file that is committed.

## 8. What this document does not cover

- The wallet's deposit and withdrawal flows, which use the PIN as described in wallet-plan 3.5 and are later phases.
- Phase 7 audit and request id plumbing, beyond the rows this design writes.
- Secret management in production. `docs/deploy.md` will list it when phase 9 writes that file.
