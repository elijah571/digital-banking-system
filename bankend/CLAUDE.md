# Working rules for bankend

These rules come from the user's review of slice 3c.5. Follow them in every session.

## Before you start

- Read `docs/wallet-plan.md` first: the status table and section 5. Then read the last entry of `docs/plan-history.md`.
- Wallet and ledger work waits for the user's approval of `docs/wallet-plan.md`. When the scope changes, put the change in the plan first, then stop for approval.

## Commits

- One slice per commit. Each commit leaves the build and all tests green.
- Commit only the files for the slice. Leave other uncommitted work in the tree alone.
- Never push.

## Money paths and smoke tests

- After any slice that touches a money path, run `scripts/smoke-throwaway.sh` on the commit.
- Never smoke on dev data. The throwaway script uses its own compose project and fresh volumes.

## Evidence and design

- Gather evidence before a fix. Reproduce the failure first.
- Ask before any design change.

## Secrets and config

- Never print secrets: keys, passwords, tokens, or `.env` values.
- `.env.example` is the user's file. Change only the lines the user asks for.

## Process

- One background job at a time, with one watch on it.
- No placeholders: no TODO stubs or fake values in committed code.
