# Wallet & P2P Transfer Service

A small wallet service with peer-to-peer transfers, built for correctness under
concurrency and failure. Money is always **integer paise** — never floats.

Built with Java 21, Spring Boot 3.4, PostgreSQL (via `NamedParameterJdbcTemplate`
with explicit SQL — no ORM hiding the locking), and Flyway migrations.

## API

All endpoints require a bearer token (`Authorization: Bearer <token>`); the token
value identifies the user. Actuator endpoints are open so logs/metrics are public.

| Method | Path | Description |
| ------ | ---- | ----------- |
| `POST` | `/wallets` | Get-or-create the caller's wallet. Returns `{id, user_id, balance_paise}`. |
| `GET`  | `/wallets/{id}` | Current balance. |
| `POST` | `/wallets/{id}/deposit` | Add outside funds (funding boundary). Body: `{amount_paise, idempotency_key}`. |
| `POST` | `/transfers` | Move money. Body: `{from, to, amount_paise, idempotency_key}`. |
| `GET`  | `/transfers/{id}` | Transfer status (`COMPLETED` / `DECLINED`). |
| `GET`  | `/actuator/health` | Health (liveness/readiness). |
| `GET`  | `/actuator/prometheus` | Metrics. |

`POST /transfers` always responds `200 OK` with the transfer resource (status
`COMPLETED` or `DECLINED`). A retry with the same `idempotency_key` returns the
identical body; the same key with a different body is `409 Conflict`.

### Example

```bash
# create two wallets
A=$(curl -s -XPOST localhost:8080/wallets -H 'Authorization: Bearer alice' | jq -r .id)
B=$(curl -s -XPOST localhost:8080/wallets -H 'Authorization: Bearer bob'   | jq -r .id)

# fund alice, then transfer 50.00 (5000 paise) to bob
curl -s -XPOST localhost:8080/wallets/$A/deposit -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' -d '{"amount_paise":100000,"idempotency_key":"seed-1"}'

curl -s -XPOST localhost:8080/transfers -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d "{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":5000,\"idempotency_key\":\"xfer-1\"}"
```

## Run it (one command)

```bash
docker compose up --build
```

This starts Postgres and the app; the app waits for Postgres to be healthy, runs
Flyway migrations, and comes up on <http://localhost:8080>. Both containers have
healthchecks.

## Run the burst script (reproduces the graded invariants)

With the stack running:

```bash
./scripts/burst.sh http://localhost:8080
```

It reproduces, and asserts, all three invariants and exits non-zero on any
violation:

1. **Concurrent get-or-create** — 50 simultaneous `POST /wallets` for a fresh user → exactly one wallet.
2. **Idempotent retry storm** — same transfer key fired 30× concurrently → one debit/credit, identical responses.
3. **Conservation under contention** — 200 concurrent transfers among 5 wallets (including A→B and B→A) → total balance unchanged, no negative balance.

Requires `bash`, `curl`, `jq`.

## Tests

Integration tests run the four invariants against a real Postgres via
Testcontainers (needs a running Docker):

```bash
mvn test
```

## Observability

- **Structured JSON logs** (ECS format) — every line carries a `correlation_id`
  (honours an inbound `X-Correlation-Id`, else generated) and the domain event:
  `transfer.created`, `transfer.debited`, `transfer.credited`, `transfer.declined`,
  `transfer.idempotent_replay`, `transfer.conflict`.
- **Metrics** at `/actuator/prometheus`:
  - Request rate / error rate: `http_server_requests_seconds_count` (with `status`, `outcome`).
  - Latency p99: `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))`.
  - Domain counters: `wallet_transfers_completed_total`, `wallet_transfers_declined_total{reason="insufficient_funds"}`, `wallet_transfers_idempotent_replays_total`, `wallet_transfers_conflicts_total`.

## Deploy (free tier, ₹0)

Any container host with a free managed Postgres works (Render / Railway / Fly.io /
Koyeb). The image is a self-contained multi-stage build. Provide these env vars:

| Env | Meaning |
| --- | ------- |
| `DATABASE_URL` | e.g. `jdbc:postgresql://host:5432/db` |
| `DATABASE_USERNAME` | db user |
| `DATABASE_PASSWORD` | db password |
| `PORT` | port to listen on (Render/Railway set this automatically) |

Flyway migrations run automatically on boot.

## Configuration

See [application.yml](src/main/resources/application.yml). All datasource settings
are environment-driven with local-friendly defaults.

## Design write-up

See [docs/WRITEUP.md](docs/WRITEUP.md) for the data model, the simplest-correct
concurrency mechanism (and the heavier alternatives rejected), where idempotency
lives, the consistency/availability call, and the AI directed-vs-decided disclosure.

## Architecture & flow diagrams

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system/deployment diagram,
the transfer sequence flow, the idempotency decision flow, the startup/migration
sequence, and the data-model ER diagram (Mermaid — renders on GitHub).
