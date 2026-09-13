# Wallet Transfer — Java + Spring Boot

A complete backend project using **Java 17, Spring Boot 4.1.1, Spring JDBC, Spring Security, PostgreSQL, Flyway, Actuator and Micrometer**. Money is integer paise; transfers and their idempotency records commit atomically.

For deployed reviewer links, production demo tokens, and submission steps, see [LIVE_GUIDE.md](LIVE_GUIDE.md).

## Start the whole application

Requires Docker with Compose:

```sh
docker compose up --build -d
```

The API runs at `http://localhost:8080`. If IntelliJ is already using port 8080, run `APP_PORT=8180 docker compose up --build -d` and use `http://localhost:8180` instead; pass `BASE_URL=http://localhost:8180` to both burst scripts. PostgreSQL runs in a persistent volume and is available on local port 5433. Startup applies Flyway migrations automatically. Wait for `curl -f http://localhost:8080/healthz` to succeed before using the API.

Provision the demo balances **before creating Alice/Bob/Carol wallets through the API**:

```sh
docker compose exec -T db psql -U wallet -d wallet < scripts/seed.sql
python3 scripts/burst.py
```

Seeding creates three demo wallets with 100,000 paise each. It never overwrites or tops up an existing wallet; normal API-created wallets start at zero. Funding is an offline fixture operation, not a public endpoint.

```sh
docker compose logs -f app       # Structured JSON logs
docker compose down              # Stop, preserving wallet data
```

Compose credentials are local demo fixtures. Set private credentials and tokens for deployment.

## Run with Java or an IDE

Import `pom.xml` as a Maven project in IntelliJ IDEA, Eclipse, or VS Code and use Java 17 or newer. `WalletApplication` is the main class. Maven Wrapper is included for macOS/Linux (`./mvnw`) and Windows (`mvnw.cmd`). First use downloads Maven and dependencies.

Start PostgreSQL with `docker compose up -d db`, or provision your own empty database. Then export:

```sh
export DB_URL='jdbc:postgresql://localhost:5433/wallet'
export DB_USERNAME='wallet'
export DB_PASSWORD='wallet-local-password'
export AUTH_TOKENS='{"alice":"demo-alice-token-0001","bob":"demo-bob-token-000001","carol":"demo-carol-token-0001","fresh":"demo-fresh-token-0001"}'
./mvnw spring-boot:run
```

`.env.example` documents the variables; Spring Boot does **not** automatically import a `.env` file. Configure environment variables in your shell or IDE. The tokens map is required at startup and fails fast if malformed, empty, duplicated, or shorter than 16 characters.

Build an executable JAR:

```sh
./mvnw -DskipTests package
java -jar target/wallet-transfer-1.0.0.jar
```

The same environment variables must be set for the JAR. Default port: 8080; override with `PORT`.

## API examples

Every wallet/transfer endpoint requires `Authorization: Bearer <token>`. The bearer token identifies the user; callers cannot create or spend on behalf of another user.

Get or create Alice's wallet:

```sh
curl -s http://localhost:8080/wallets \
  -H 'Authorization: Bearer demo-alice-token-0001' \
  -H 'Content-Type: application/json' -d '{}'
```

Transfer 100 paise using the deterministic wallet IDs from the seed file:

```sh
curl -s http://localhost:8080/transfers \
  -H 'Authorization: Bearer demo-alice-token-0001' \
  -H 'Content-Type: application/json' \
  -d '{"from":"00000000-0000-4000-8000-000000000001","to":"00000000-0000-4000-8000-000000000002","amount_paise":100,"idempotency_key":"demo-order-1"}'
```

Repeat exactly that request to receive the original result without another debit. Change the amount or wallet under that key to receive `409`.

| Endpoint | Behavior |
|---|---|
| `POST /wallets` | `{}` or `{"user_id":"alice"}` matching the caller; returns `id` and `balance_paise` |
| `GET /wallets/{id}` | Current balance, visible only to the owner |
| `POST /transfers` | Atomic transfer; sender must own the source wallet |
| `POST /transfers/{id}/reverse` | Full refund, authorized by the original recipient |
| `GET /transfers/{id}` | Stored result, visible to the original sender or recipient |
| `GET /healthz` | Public database-aware health check |
| `GET /metrics` | Public Prometheus metrics |

Transfer responses contain `id`, `from`, `to`, `amount_paise`, `idempotency_key`, `status`, `reason`, and `reversal_of` (null for ordinary transfers). Status is `succeeded` or `declined`; reason is null, `insufficient_funds`, or `recipient_balance_limit`. Initial success, business decline and replay all return HTTP `200`. Declines are persisted and remain declined when retried after funding.

Amounts must be JSON integer tokens in `1..9007199254740991`: no strings, decimal notation, or exponent notation. The bound keeps JSON interoperable with JavaScript clients. Java uses `long`; PostgreSQL uses `BIGINT`. Input parsing checks the JSON node type before numeric conversion, so large fractional values cannot silently round into valid amounts. Wallet balances have the same maximum; recipient overflow declines without debiting.

Keys are scoped to the authenticated user and contain 1–128 printable ASCII characters without spaces. Unknown input fields and self-transfers are rejected. Validation/authorization errors do not reserve an idempotency key. Error statuses: `400` invalid input, `401` missing/invalid token, `403` source not owned, `404` missing/inaccessible resource, `405` method, `409` key conflict, `415` content type, `503` database unavailable or transaction timeout. Retry `503` and uncertain network outcomes with the **same key and body**: the transaction may already have committed.

A bounded caller-supplied `X-Correlation-ID` is propagated; otherwise a UUID is generated. Responses carry the ID in the header and errors also include `correlation_id`.

See `openapi.yaml` for the API contract and `requests.http` for IDE-ready requests.

## Reversal / refund transfers

Only the **original recipient** may initiate a refund, because their wallet is debited. The original sender and unrelated users receive `403`. This endpoint is not an administrative chargeback. The server derives the full amount and swapped wallets from the original transfer; the body accepts only its own idempotency key.

```sh
curl -s http://localhost:8080/transfers/<original-transfer-id>/reverse \
  -H 'Authorization: Bearer demo-bob-token-000001' \
  -H 'Content-Type: application/json' \
  -d '{"idempotency_key":"refund-order-1"}'
```

A reversal is another transfer record. Its response has the same fields as an ordinary transfer, with `reversal_of` identifying the original. `GET /transfers/{reversal-id}` returns the stored result to either participant. The original transfer keeps its original `succeeded` status and its original idempotent response; it is not rewritten.

- Same caller/key and original ID returns the exact stored refund result, including a decline.
- A different original ID or a forward-transfer operation under that key returns `409 idempotency_key_conflict`. Forward transfers and reversals share the caller/key namespace.
- A new key after a successful refund returns `409 transfer_already_reversed`, even under concurrent requests.
- Missing original: `404`. A declined original: `409 transfer_not_succeeded`. A reversal cannot itself be reversed: `409 cannot_reverse_reversal`.
- If the recipient has spent the money, the reversal returns HTTP `200` with `status: declined` and `reason: insufficient_funds`; no balance changes. After funding, use a new key to make another attempt. Recipient balance capacity is checked as well.
- Partial refunds and caller-supplied amounts/directions are rejected. Authentication and body validation errors do not claim keys.

Flyway `V2__transfer_reversals.sql` adds the nullable original-transfer foreign key and a partial unique index allowing only one **successful** reversal per original. It preserves all existing transfers. Restarting the new JAR against a V1 database applies the upgrade automatically; do not edit or reapply V1 manually. Upgrade all app instances before enabling refunds; do not keep pre-reversal code serving requests during rollout, because that older code does not distinguish reversal keys from normal transfer keys.

Run the standalone reversal probes after seeding demo wallets:

```sh
python3 scripts/reversal-burst.py
# Optional: alternate between two app instances sharing a database:
BASE_URLS=http://localhost:8080,http://localhost:8081 python3 scripts/reversal-burst.py
```

The script tests same-key storms, new-key double-refund rejection, competing keys and insufficient funds with persistent decline/retry. It restores initial balances after a successful run; use isolated demo wallets without unrelated traffic.

## Tests

Docker test-runner configuration (currently unverified; see verification status below):

```sh
docker compose --profile test run --build --rm tests
```

Or with an existing PostgreSQL database:

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5433/wallet \
TEST_DB_USERNAME=wallet TEST_DB_PASSWORD=wallet-local-password \
./mvnw verify
```

Use a test database. Tests create unique users, migrate the schema, and remove only their own rows afterward. They never truncate application tables. Tests require PostgreSQL; there is no H2 substitute and a missing database fails the suite rather than silently skipping it.

The integration suite also covers reversal retry storms, distinct-key races, authorization, changed-original and cross-operation key conflicts, persistent declines, concurrent spending, rollback, receiver capacity and the SQL unique refund constraint. The base suite exercises 50 concurrent wallet creations, a 30-request same-key storm, competing bodies under one key, 360 contended transfers, persistent declines, ownership and malformed input, exact maximum amounts, caller-scoped keys, SQL balance constraints, metrics, and a failure injected at the credit operation after a real debit. That last test verifies both balance rollback and removal of the uncommitted key, then successfully retries. Mockito wraps the real repository only for fault injection; money assertions query real PostgreSQL.

Standalone load reproduction uses only Python 3's standard library:

```sh
BASE_URL=https://your-api.example AUTH_TOKENS='{"alice":"...","bob":"...","carol":"...","fresh":"..."}' python3 scripts/burst.py
```

Run against seeded, isolated demo wallets without unrelated writers. To repeat the fresh-user creation race, configure a new server-side user/token and supply its token as `fresh` to the script. Ordinary retries on an already-created wallet are still checked for a stable ID.

## Project layout

```text
src/main/java/com/example/wallet/
  WalletApplication.java
  config/
    SecurityConfig.java
    MoneyLimits.java
  controller/
    WalletController.java
    TransferController.java
  service/
    WalletService.java
    TransferService.java
  repository/
    WalletRepository.java
    TransferRepository.java
  entity/
    WalletEntity.java
    TransferEntity.java
  model/
    WalletResponse.java
    TransferResponse.java
    TransferCommand.java
    ReversalCommand.java
    TransferOutcome.java
  mapper/
    WalletMapper.java
    TransferMapper.java
  validation/
    Input.java
  exception/
    ApiException.java
    ApiErrorHandler.java
  observability/
    RequestLoggingFilter.java
    TransferTelemetry.java
src/main/resources/
  application.yaml
  db/migration/V1__wallet_schema.sql
  db/migration/V2__transfer_reversals.sql
src/test/java/com/example/wallet/WalletIntegrationTest.java
scripts/seed.sql / scripts/burst.py / scripts/reversal-burst.py
Dockerfile / compose.yaml / .github/workflows/ci.yaml
```

Each model and entity is a separate top-level Java file. `entity` represents PostgreSQL rows, including internal user IDs and transfer timestamps; `model` contains API responses and service commands/results. `mapper` converts entities to API responses so persistence fields are not exposed accidentally. Entities are plain Java records mapped with Spring JDBC, not JPA-managed objects.

Wallet and transfer controllers, services, and repositories are separate. `TransferService` owns the single transaction spanning both repositories; splitting the packages does not split the money movement into separate transactions. Controllers handle HTTP, services enforce business rules, and repositories return persistence entities rather than API DTOs. Shared authentication, validation, exception handling, and telemetry live in their own packages.

## Observability and deployment

Actuator exposes request counts and latency histograms at `/metrics`. Useful PromQL:

```promql
sum(rate(http_server_requests_seconds_count{uri!~"/healthz|/metrics"}[5m]))
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/healthz|/metrics"}[5m])))
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 0.000001)
sum by (event) (rate(wallet_events_total[5m]))
```

Reversal-specific counter labels are `reversal_created`, `reversal_succeeded`, and `reversal_declined`; reversal logs include the original transfer ID. Existing transfer counters also count reversal movements.

Domain counter labels: `created` (includes persisted declines), `declined_insufficient_funds`, `declined_recipient_balance_limit`, and `idempotent_replay`. JSON logs include correlation IDs and transfer-created, debit, credit, decline and replay events. Domain telemetry is emitted only after the Spring transaction proxy returns successfully. A crash after commit can lose telemetry; database transfer records remain authoritative. Counters are per-process and reset on restart.

The Dockerfile builds the executable JAR in a separate stage, runs as a non-root user, and includes a database-aware health check. For hosting, set `DB_URL` (a JDBC URL, not `postgres://`), `DB_USERNAME`, `DB_PASSWORD`, `AUTH_TOKENS`, and optionally `PORT`. Use certificate-verified PostgreSQL TLS for a remote database. Flyway requires schema-creation permissions at first startup. Logs stream to stdout.

## Postman

Import the collection and local environment from `postman/`. See `postman/README.md` for setup and request ordering. Use deployment-specific tokens in your private Postman environment.

## Local React UI

The frontend is in `frontend/`. Run `cd frontend && npm ci && npm run dev`, then open http://localhost:5173 with the API running on port 8180. See [frontend/README.md](frontend/README.md) for the payment/refund walkthrough, test commands, and hosting considerations.
