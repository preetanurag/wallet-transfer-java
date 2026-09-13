# Local wallet UI

React + Vite interface for the existing Java/Spring Boot API. All payment rules remain enforced by the backend and PostgreSQL.

## Start locally

Use Node.js 22.12+ (Node 24 LTS recommended), npm, and the project's running Docker backend on port 8180.

From the project root:

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. The Vite server proxies `/api/*` to `http://127.0.0.1:8180`; no backend CORS changes are necessary. To change the API port, copy `.env.example` to `.env.local` and set `WALLET_API_TARGET`, then restart Vite.

## Try the complete flow

1. Click **Bob**, then **Copy wallet ID**. Demo shortcuts appear only in development mode.
2. Click **Alice**, paste Bob's wallet ID, and enter `1.23` (rupees).
3. Click **Review transfer**, check the recipient and amount, then **Confirm transfer**.
4. Copy the resulting transfer ID and click **Done**.
5. Switch to **Bob**. Paste the transfer ID under **Find a transfer**, then click **Look up**.
6. Choose **Review refund** and **Confirm refund**. The full ₹1.23 returns to Alice.
7. Look up the original again and attempt another refund: the backend rejects a double refund.
8. Try an amount above the sending balance: the result is **Declined** and no money moves. Try `1.001` or a malformed wallet ID: the UI rejects it before submission.

You may instead connect with a bearer token. Tokens are kept only in memory. Switching accounts or reloading clears session activity; this list is not a complete transfer history. Existing transfers remain accessible by ID. Fresh wallets start at zero; the UI does not mint funds. Demo balances come from the existing seed script and change as tests run.

After an uncertain network response, keep the page open and use **Retry same request**. It reuses the identical idempotency key and body. Do not reload until resolved: pending requests are held in memory. Production builds omit demo shortcuts and their embedded demo tokens.

## Automated checks

```sh
npm test
npm run build
npm run preview
```

Preview serves the production build at http://localhost:4173 with the same local API proxy. Enter a token manually in preview mode.

The frontend tests cover exact monetary boundaries, invalid input, review-before-send, lost-response retries, declines, and recipient refunds. The project's existing Maven integration tests and Python concurrency scripts remain applicable; see the root README.

## Structure

- `src/App.jsx`: connection, balance, transfer review, lookup, refund, and session activity
- `src/api.js`: authenticated API requests, timeout, correlation IDs, uncertain-result classification
- `src/money.js`: exact rupee/paise conversion and formatting
- `src/style.css`: responsive layout, focus states, dialogs
- `tests/`: React behavior and money validation tests
- `vite.config.js`: local dev/preview API proxy and test configuration

## Hosting later

This change is local only. A deployed static UI will need an `/api` reverse proxy to the deployed Spring Boot service (or an explicitly configured API origin and restricted backend CORS). Vite's local proxy does not ship inside `dist`. Use HTTPS and real deployment credentials before sharing externally. No hosting resources have been created.
