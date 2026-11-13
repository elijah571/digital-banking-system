# Eli‑Pay UI

> **Eli‑Pay** is a responsive, component‑driven prototype of a digital‑banking front‑end built with **React**, **JavaScript**, and **Tailwind CSS**. It showcases the full customer journey – from onboarding to transfers, deposits, withdrawals, identity verification, and support – using static demo data. The UI is ready to be hooked up to real banking APIs.

---

## Table of Contents
- [Demo](#demo)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Setup & Development](#setup--development)
- [API Integration (RTK Query)](#api-integration-rtk‑query)
- [Branding & Theming](#branding--theming)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)

---

## Demo
You can run the UI locally (see *Setup & Development*). The app works entirely in the browser; no backend services are required for the demo.

---

## Features
- **Full customer flow** – onboarding, dashboard, add money, send money, withdraw, identity verification, notifications, settings, and support.
- **Responsive design** – mobile‑first layout that adapts to tablets and desktops.
- **Component library** – reusable UI primitives (`Button`, `Panel`, `TopBar`, `Footer`, etc.) built with Tailwind CSS.
- **State management** – Redux Toolkit + RTK Query for data fetching, caching, loading & error handling.
- **Brand migration** – all UI strings use the new *Eli‑Pay* branding while preserving the original visual theme.
- **Demo‑only data** – static JSON fixtures for accounts, transactions, beneficiaries, and notifications.
- **Extensible architecture** – clear separation of `pages/`, `components/`, `store/api/`, and `models/`.

---

## Tech Stack
| Layer | Technology |
|-------|------------|
| UI Framework | **React** (hooks) |
| Language | **JavaScript** (ES6+) |
| Styling | **Tailwind CSS** (utility‑first) |
| State Management | **Redux Toolkit** & **RTK Query** |
| Build Tool | **Vite** (fast HMR) |
| Testing | **Jest**, **React Testing Library**, **Cypress** |
| Linting/Formatting | **ESLint**, **Prettier** |

---

## Project Structure
```
frontend/
├─ src/
│  ├─ components/          # Reusable UI components (Button, TopBar, Footer, …)
│  │  └─ layout/            # Layout‑specific components
│  ├─ pages/                # Route‑level pages (Dashboard, AddMoney, …)
│  ├─ store/                # Redux store configuration
│  │  └─ api/               # RTK Query endpoint definitions
│  ├─ models/               # Type definitions & static data
│  ├─ hooks/                # Custom React hooks
│  ├─ assets/               # Images, icons, etc.
│  └─ App.jsx               # App bootstrap & routing
├─ public/                  # Static public assets (favicon, etc.)
├─ vite.config.js           # Vite configuration
├─ tailwind.config.js       # Tailwind configuration
└─ README.md                # ← This file
```

---

## Setup & Development
### Prerequisites
- **Node.js ≥ 18**
- **npm** (comes with Node) or **yarn**

### Installation
```bash
# Clone the repository (if you haven't already)
git clone https://github.com/your‑org/eli‑pay-ui.git
cd eli‑pay-ui/frontend

# Install dependencies
npm install   # or `yarn`
```

### Run locally
```bash
npm run dev   # Starts Vite dev server at http://localhost:5173
```
The app hot‑reloads on file changes.

### Build for production
```bash
npm run build   # Generates optimized static assets in ./dist
```

---

## API Integration (RTK Query)
The UI is wired with **RTK Query** to demonstrate how real endpoints would be consumed.
- **Endpoints** are defined in `src/store/api/` (e.g., `accountApi`, `transactionApi`).
- Each endpoint returns `data`, `isLoading`, `isError`, and `error` fields that the UI components consume to display spinners, toast alerts, and fallback UI.
- **Mock data** lives in `src/models/` and is used when the API layer is disabled (default for the demo).
- To hook up a real backend, replace the `baseUrl` in `src/store/api/baseApi.js` with your service URL and adjust the request/response schemas accordingly.

---

## Branding & Theming
All UI copy now uses **Eli‑Pay** (e.g., “© 2026 Eli‑Pay”). The brand name appears in:
- Footer, header, and page titles.
- Notification messages and security warnings.
- Static data objects (`initialTransactions`, `initialNotifications`).
The visual theme (colors, spacing, typography) remains unchanged – only the textual branding was updated.

---

## Testing
```bash
# Unit & component tests
npm run test

# End‑to‑end tests (Cypress)
npm run cypress:open
```
The test suite checks routing, component rendering, and RTK Query loading states.

---

## Contributing
1. Fork the repository.
2. Create a feature branch (`git checkout -b feat/awesome‑feature`).
3. Make your changes and ensure tests pass.
4. Submit a Pull Request with a clear description of the change.

Please follow the existing code style (ESLint + Prettier) and include unit tests for new functionality.

---

## License
This project is licensed under the **MIT License** – see the `LICENSE` file for details.

---

*Happy coding!*