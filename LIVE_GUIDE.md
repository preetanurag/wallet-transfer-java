# Live Submission Guide

This guide gives reviewers the links, demo credentials, request examples, and local run commands needed to evaluate the wallet transfer project.

## What To Submit

Submit these items:

- Public GitHub repository: https://github.com/preetanurag/wallet-transfer-java
- Live UI: https://wallet-transfer-java.vercel.app
- Live backend API: https://wallet-transfer-api-wtnh.onrender.com
- Live health check: https://wallet-transfer-api-wtnh.onrender.com/healthz
- Burst scripts: `scripts/burst.py` and `scripts/reversal-burst.py`
- Design write-up: `DESIGN.md`
- This live guide: `LIVE_GUIDE.md`

If the form has room for notes, mention that Render's free service can sleep and the first request may take about a minute.

## Submission Links

| Item | Link |
| --- | --- |
| GitHub repository | https://github.com/preetanurag/wallet-transfer-java |
| Live UI | https://wallet-transfer-java.vercel.app |
| Live backend base URL | https://wallet-transfer-api-wtnh.onrender.com |
| Backend health check | https://wallet-transfer-api-wtnh.onrender.com/healthz |
| UI-proxied health check | https://wallet-transfer-java.vercel.app/api/healthz |

The frontend is deployed on Vercel. The backend is deployed on Render as a Docker web service. PostgreSQL is hosted on Supabase.

## Demo Tokens

Every wallet and transfer endpoint requires a bearer token. The token identifies the caller.

| User | Bearer token |
| --- | --- |
| alice | `E-nm_NP4Yf2okAH0diFrEc53IKXscU6q` |
| bob | `RHhLpsMwaV5aWkMj24gNZOZYSStibc33` |
| carol | `9bLZTjROb-6tPFZC2wJo8r4hr4ycAb9B` |
| fresh | `G11eVFbZupLbg2V5vnSOFRHRIre37tXa` |

Alice, Bob, and Carol are seeded with `100000` paise each for the live demo. The `fresh` user starts at zero and is useful for validating new-wallet behavior.

## Live UI Walkthrough

1. Open https://wallet-transfer-java.vercel.app.
2. Click the **Alice** demo account button, or paste Alice's bearer token and click **Connect**.
3. Alice's wallet should load with a balance of `100000` paise.
4. To transfer money to Bob, use Bob's seeded wallet ID:

```text
00000000-0000-4000-8000-000000000002
```

5. Enter an amount in rupees, review, and confirm.
6. Copy the returned transfer ID from the activity panel.
7. Disconnect, then click the **Bob** demo account button or connect with Bob's token.
8. Look up the transfer ID and issue a refund. Only Bob, the original recipient, can reverse that transfer.

## What Can Be Checked From The UI

The UI is useful for checking the user-facing behavior, but the grading invariants are best verified with the scripts and API commands below.

| Requirement | UI check | Stronger check |
| --- | --- | --- |
| `POST /wallets` get-or-create | Click a demo account multiple times and confirm the same wallet loads. | `scripts/burst.py` fires 50 concurrent wallet creates for one fresh user. |
| `GET /wallets/{id}` | Connect and click **Refresh**. | Curl or Postman `GET /wallets/{id}` with the owner's token. |
| `POST /transfers` | Alice sends money to Bob. | Curl/Postman with a chosen `idempotency_key`. |
| `GET /transfers/{id}` | Paste a transfer ID into **Find or refund**. | Curl/Postman `GET /transfers/{id}` as sender or recipient. |
| No overdraft | Try sending more than the connected wallet balance; the UI should show a declined result. | Burst script verifies no negative balances after contention. |
| Conservation | Send Alice to Bob, then Bob refunds; balances return to the starting total. | Burst script verifies total balance is unchanged under concurrent transfers. The local Docker run can be increased to 360 transfers for heavier stress. |
| Reversal/refund | Alice sends, Bob looks up the transfer, Bob clicks **Review full refund**. | `scripts/reversal-burst.py` verifies concurrent refund retries and double-refund protection. |
| Idempotent retry | The UI preserves a generated key only for uncertain network retries. Normal successful UI transfers intentionally generate a new key each time. | Curl/Postman/scripts can reuse the exact same `idempotency_key` and prove one debit/credit. |
| Same-key different-body conflict | Not exposed in the UI because it is a developer/API concern. | Curl/Postman: reuse a key with a changed amount or wallet and expect `409`. |
| Race-free get-or-create | Not meaningfully visible from one browser session. | `scripts/burst.py` is the intended proof. |

## Idempotency API Proof

The UI hides idempotency keys because normal users should not manage request keys manually. To prove idempotency to a reviewer, use the API.

First request:

```bash
curl -s https://wallet-transfer-api-wtnh.onrender.com/transfers \
  -H 'Authorization: Bearer E-nm_NP4Yf2okAH0diFrEc53IKXscU6q' \
  -H 'Content-Type: application/json' \
  -d '{
    "from": "78864aaf-0ff1-4dcd-bf42-c6a7a313e5c0",
    "to": "00000000-0000-4000-8000-000000000002",
    "amount_paise": 100,
    "idempotency_key": "review-idempotency-demo-1"
  }'
```

Run the exact same command again. The response should contain the same transfer ID and no second debit.

Then reuse the same key with a different amount:

```bash
curl -i -s https://wallet-transfer-api-wtnh.onrender.com/transfers \
  -H 'Authorization: Bearer E-nm_NP4Yf2okAH0diFrEc53IKXscU6q' \
  -H 'Content-Type: application/json' \
  -d '{
    "from": "78864aaf-0ff1-4dcd-bf42-c6a7a313e5c0",
    "to": "00000000-0000-4000-8000-000000000002",
    "amount_paise": 101,
    "idempotency_key": "review-idempotency-demo-1"
  }'
```

Expected result: HTTP `409` with `idempotency_key_conflict`.

## Live API Examples

Create or fetch Alice's wallet through the deployed backend:

```bash
curl -s https://wallet-transfer-api-wtnh.onrender.com/wallets \
  -H 'Authorization: Bearer E-nm_NP4Yf2okAH0diFrEc53IKXscU6q' \
  -H 'Content-Type: application/json' \
  -d '{}'
```

Transfer `100` paise from Alice to Bob:

```bash
curl -s https://wallet-transfer-api-wtnh.onrender.com/transfers \
  -H 'Authorization: Bearer E-nm_NP4Yf2okAH0diFrEc53IKXscU6q' \
  -H 'Content-Type: application/json' \
  -d '{
    "from": "78864aaf-0ff1-4dcd-bf42-c6a7a313e5c0",
    "to": "00000000-0000-4000-8000-000000000002",
    "amount_paise": 100,
    "idempotency_key": "review-demo-transfer-1"
  }'
```

Reverse a successful transfer as Bob:

```bash
curl -s https://wallet-transfer-api-wtnh.onrender.com/transfers/<transfer-id>/reverse \
  -H 'Authorization: Bearer RHhLpsMwaV5aWkMj24gNZOZYSStibc33' \
  -H 'Content-Type: application/json' \
  -d '{"idempotency_key":"review-demo-refund-1"}'
```

Use a new idempotency key for a new operation. Reusing the same key with the same body returns the stored result. Reusing the same key with a different operation returns a conflict.

## What Is Deployed Where

| Technology | Purpose |
| --- | --- |
| Java 17 | Backend runtime language |
| Spring Boot | HTTP API, configuration, validation, security, health endpoints |
| Spring JDBC | Database access without JPA magic |
| PostgreSQL | Durable wallet, transfer, idempotency, and reversal storage |
| Flyway | Database migrations, including the reversal schema |
| Supabase | Hosted PostgreSQL database |
| Docker | Builds and runs the Spring Boot backend on Render |
| Render | Hosts the backend container |
| React + Vite | Browser UI for wallet, transfer, lookup, and refund flows |
| Vercel | Hosts the static React UI and proxies `/api/*` to Render |
| GitHub Actions | CI for backend tests, frontend tests/build, and Docker build |
| Postman | Manual API testing collection |

## How Docker Is Used In The Live Deployment

Docker is used for the backend, not the frontend.

Render reads the repository's `Dockerfile`, builds the Spring Boot JAR in a build stage, copies the JAR into a smaller Java runtime image, and runs that image as the web service. The same Dockerfile can be used locally with Docker Compose.

The live frontend is a static Vite build on Vercel. It does not run in Docker. Vercel serves the generated files from `frontend/dist` and uses the root `vercel.json` rewrite so browser requests to `/api/*` go to the Render backend.

The live PostgreSQL database is not running in Docker. It is managed by Supabase.

## Local Run

Run the full local backend stack with Docker Compose:

```bash
docker compose up --build -d
docker compose exec -T db psql -U wallet -d wallet < scripts/seed.sql
curl -f http://localhost:8080/healthz
```

If port `8080` is busy:

```bash
APP_PORT=8180 docker compose up --build -d
curl -f http://localhost:8180/healthz
```

Run the local UI:

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173.

## Verification Commands

Backend integration tests:

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5433/wallet \
TEST_DB_USERNAME=wallet \
TEST_DB_PASSWORD=wallet-local-password \
./mvnw verify
```

Frontend tests and build:

```bash
cd frontend
npm test
npm run build
```

Concurrency probes:

```bash
BASE_URL=http://localhost:8180 python3 scripts/burst.py
BASE_URL=http://localhost:8180 python3 scripts/reversal-burst.py
```

For the live API on the free Render/Supabase deployment, use this hosted-friendly command. It still checks the required races and invariants, while avoiding overloading the free database pool during review:

```bash
BASE_URL=https://wallet-transfer-api-wtnh.onrender.com \
CONTENDED_TRANSFERS=30 \
BURST_WORKERS=10 \
AUTH_TOKENS='{"alice":"E-nm_NP4Yf2okAH0diFrEc53IKXscU6q","bob":"RHhLpsMwaV5aWkMj24gNZOZYSStibc33","carol":"9bLZTjROb-6tPFZC2wJo8r4hr4ycAb9B","fresh":"G11eVFbZupLbg2V5vnSOFRHRIre37tXa"}' \
python3 scripts/burst.py
```

For the heavier local Docker stress run, use:

```bash
BASE_URL=http://localhost:8180 CONTENDED_TRANSFERS=360 python3 scripts/burst.py
```

For live reversal/refund checks:

```bash
BASE_URL=https://wallet-transfer-api-wtnh.onrender.com \
AUTH_TOKENS='{"alice":"E-nm_NP4Yf2okAH0diFrEc53IKXscU6q","bob":"RHhLpsMwaV5aWkMj24gNZOZYSStibc33","carol":"9bLZTjROb-6tPFZC2wJo8r4hr4ycAb9B"}' \
python3 scripts/reversal-burst.py
```

## Postman

Import:

- `postman/Wallet.postman_collection.json`
- `postman/Local.postman_environment.json`

For live testing, duplicate the local environment and set:

```text
base_url = https://wallet-transfer-api-wtnh.onrender.com
alice_token = E-nm_NP4Yf2okAH0diFrEc53IKXscU6q
bob_token = RHhLpsMwaV5aWkMj24gNZOZYSStibc33
carol_token = 9bLZTjROb-6tPFZC2wJo8r4hr4ycAb9B
fresh_token = G11eVFbZupLbg2V5vnSOFRHRIre37tXa
```

## Notes For Reviewers

- Money is stored as integer paise.
- New API-created wallets start with zero balance.
- Demo funding is an offline seed operation, not a public endpoint.
- Transfers are atomic and idempotent.
- Reversals are full refunds and are authorized only by the original recipient.
- Render's free instance can sleep after inactivity, so the first request may take about a minute.
