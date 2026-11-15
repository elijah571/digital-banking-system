# Fraud Detection and Prevention

## Existing controls

Fraud-detection-service evaluates transfer/payout and crypto amount, velocity, and balance signals. Transfer review can require OTP; withdrawals use PIN and a risk decision before reservation/submission. Suspicious crypto withdrawals enter admin review. Decisions and sensitive ledger activity are auditable.

## Limits

Current scoring is rule-based and has no device identity, trusted beneficiary age, IP reputation, impossible-travel signal, graph analysis, or analyst case-management workflow. Email OTP is not phishing resistant and must not be the only step-up factor for high-risk operations. Current rules are not a calibrated machine-learning score and must not automatically freeze funds from one unverified signal.

## Safe extension

- Record reason codes and model/rule versions with each decision.
- Keep low/medium/high/critical thresholds configurable and reviewed.
- Hold only the transaction in question pending independent review; retain customer access to unrelated funds.
- Add a new-beneficiary cooling period, device binding, and stronger step-up authentication before using login/geographic signals.
- Measure false-positive and false-negative rates from reviewed outcomes.
- Alert, audit, and test provider/webhook anomalies separately from customer transaction risk.
