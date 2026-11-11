# Eli Digital Bank UI

Responsive React + JavaScript + Tailwind CSS prototype based on the screens in `../bankend/docs/eli_digital_banking_full_screen_ui_slides-1.pdf`.

## Run locally

```sh
npm install
npm run dev
```

The interface uses local demo data only. It does not call the banking API or move real money. Customer flows, sample account balances, transaction history, and operations actions are held in browser memory and reset on refresh. The screens are structured to be connected to the backend services later.

Customer pages include account and transaction views, transfers, deposits, withdrawals, identity verification, saved beneficiaries, notifications, settings, and support. Beneficiaries and notification read states are demo-only and reset when the page is refreshed.
