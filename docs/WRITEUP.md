# Wallet & P2P Transfer — Design Write-up

## Data model

Three tables (see [migrations](../src/main/resources/db/migration)):

- **`wallets`** — `id`, `user_id` **UNIQUE**, `balance_paise BIGINT`, timestamps.
  `CHECK (balance_paise >= 0)`.
- **`transfers`** — `id`, `idempotency_key` **UNIQUE**, `from_wallet_id`,
  `to_wallet_id`, `amount_paise BIGINT`, `status` (`PENDING`/`COMPLETED`/`DECLINED`),
  `request_hash`, `created_at`. Doubles as the idempotency ledger.
  `CHECK (amount_paise > 0)`, `CHECK (from <> to)`.
- **`deposits`** — the funding boundary (where outside money enters). `id`,
  `idempotency_key` **UNIQUE**, `wallet_id`, `amount_paise`, `created_at`.

Money is `BIGINT` paise everywhere — storage, wire, and computation. No floats,
ever. Balances are non-negative by DB constraint, not just by app logic.

## The simplest-correct mechanism for conservation + no-overdraft

A single READ COMMITTED transaction does the whole move:

1. **Lock both wallet rows `FOR UPDATE` in ascending `id` order, one row per
   statement** (`SELECT id FROM wallets WHERE id = :id FOR UPDATE`, issued first
   for the lower id, then the higher), before any other row lock in the
   transaction.
2. **Atomic conditional debit:** `UPDATE wallets SET balance_paise = balance_paise - :amt
   WHERE id = :from AND balance_paise >= :amt`. Rows-affected = 0 ⇒ the balance was
   insufficient ⇒ decline cleanly (mark `DECLINED`, no credit, commit). No
   read-modify-write in app code, so there is no lost update.
3. **Credit:** `UPDATE ... SET balance_paise = balance_paise + :amt WHERE id = :to`.

**Why this is the simplest correct thing.** The conditional `UPDATE` makes the
overdraft check and the debit one atomic step, so "check balance then subtract"
can never interleave. Conservation falls out for free: every transfer is one
subtract and one matching add inside one transaction — it commits together or not
at all.

**Deadlock avoidance.** The danger is A→B and B→A running at once and locking the
two rows in opposite orders. I acquire the row locks in a single global order
(ascending `id`) so every transaction that touches the same pair locks them in the
same sequence, so the AB–BA cycle cannot form. Two details make this actually work:

- *One row per `SELECT ... FOR UPDATE`, not `WHERE id IN (a,b) ORDER BY id`.*
  Postgres acquires `FOR UPDATE` locks in **scan (heap) order** and applies the
  `ORDER BY` only afterwards, so with random-UUID ids a single `IN (...) ORDER BY`
  statement does not control the lock order and still deadlocks. Locking one row at
  a time, in sorted order, is what pins the order.
- *Lock the wallets before the `transfers` INSERT.* The transfers row has foreign
  keys to both wallets, so inserting it takes a `KEY SHARE` lock on each referenced
  wallet row (in unsorted from/to order). If the `FOR UPDATE` came after, it would
  try to **upgrade** those `KEY SHARE` locks to exclusive, and two opposite
  transfers deadlock on the upgrade. Taking `FOR UPDATE` first (in sorted order)
  means the later FK `KEY SHARE` is already subsumed — no upgrade, no deadlock.

I debit before crediting, so a decline needs no rollback of an already-applied
credit. Verified: the burst script fires hundreds of concurrent A→B and B→A
transfers and asserts **zero 5xx** alongside conservation.

**Heavier alternatives I rejected.**
- *`SERIALIZABLE` isolation everywhere.* Correct, but it pushes the cost onto the
  caller: under contention Postgres aborts transfers with a serialization error
  (SQLSTATE `40001`), so every caller has to catch that and retry in a loop.
  Unnecessary here, because the only thing I need to guard is a single wallet's
  balance, and the conditional `UPDATE` already makes that check-and-subtract one
  atomic step.
- *Read balance into the app, subtract, write back.* The classic lost-update bug —
  two transfers both read ₹100, both write ₹50, ₹50 vanishes. Explicitly avoided.
- *Unsorted `SELECT ... FOR UPDATE`.* Correct on balances but deadlocks under the
  A→B + B→A cross. Sorting the lock order is what fixes it.

## Where idempotency lives

In the database, on `transfers.idempotency_key` (UNIQUE), claimed **in the same
transaction as the debit/credit** via `INSERT ... ON CONFLICT (idempotency_key)
DO NOTHING`:

- If the insert affects 1 row, this caller owns the transfer and does the money move.
- If it affects 0 rows, the key already exists: I read the existing transfer and
  return it (an idempotent replay). A second request with the same key waits on the
  unique index until the first one commits, then reads that committed result. So
  there is no gap between "check if it already ran" and "run the transfer" — that
  gap is the classic TOCTOU (time-of-check-to-time-of-use) race — and no double
  debit even under a storm of retries.
- I use `ON CONFLICT DO NOTHING` rather than catching a unique-violation because a
  raised violation aborts the whole Postgres transaction, which would stop me
  reading the existing row in the same tx.

**Same key, different body** → `409 Conflict`. I store a SHA-256 `request_hash` of
`(from, to, amount)`; on a replay whose hash differs from the stored one, I reject
with 409 instead of silently returning the first result.

Deposits use the identical pattern on `deposits.idempotency_key`, so a retried
top-up never credits twice.

## Consistency vs availability

This is money, so I chose **consistency (CP)**. A transfer touches a single
Postgres primary inside one ACID transaction; if the database is unreachable the
writes fail fast rather than accepting a transfer we cannot durably record.

What I consciously gave up: horizontal write scaling and availability during a DB
outage. A single primary is a bottleneck and a single point of failure. For a
wallet, briefly refusing writes is far cheaper than double-spending or losing
money, and the invariants (conservation, no-overdraft, exactly-once) are trivially
easier to guarantee against one authoritative store. The app layer itself is
stateless, so it scales out freely — all correctness lives in the database.

## Scaling a hot wallet

The one contention point is a single wallet row hit by a very large number of
concurrent transfers: they serialize on that row's lock. For the assessment load
this is fine, and the conditional `UPDATE` keeps each hold extremely short. If a
single wallet needed far higher throughput, I would move that wallet's transfers
onto a **message queue partitioned by wallet id** (e.g. Kafka): all writes to the
same wallet land on the same partition and are drained in order by one consumer, so
the debit/credit is applied sequentially without lock contention, while different
wallets scale out across partitions. This trades a little latency (async apply) for
throughput on hot rows, and keeps exactly-once via the same idempotency key on the
consumer side. I deliberately did **not** build this now — it is unnecessary
complexity for the required load, and the single-primary + row-lock design is the
simplest thing that is correct.

## Deploy, containerize, observe

**Live URL:** https://wallet-transfer-service-8kt0.onrender.com
**Repo:** https://github.com/dineshbish/wallet-transfer-service

**Container.** A multi-stage Dockerfile: a Maven/JDK stage builds the jar, a slim
JRE stage runs it. It runs as a **non-root** user and defines a `HEALTHCHECK` that
curls `/actuator/health/liveness`. `docker compose up --build` brings up the app
plus Postgres with **one command**; the app waits for Postgres's healthcheck before
starting, and Flyway applies the schema migrations (`V1`–`V3`) on boot, so a fresh
clone is correct the moment it is healthy.

**Deploy.** Deployed to Render from a checked-in [`render.yaml`](../render.yaml)
blueprint that provisions a free managed Postgres and the web service and wires the
database connection into the app. App and database are pinned to the same region so
the app resolves the database's internal hostname. Cost is **₹0** — no card, no paid
add-ons.

**Logs.** Structured **JSON** (ECS format), one object per line, each carrying a
`correlation_id` (honours an inbound `X-Correlation-Id`, else generated per request)
so a single request can be traced end to end. Every meaningful domain event is
logged: `transfer.created`, `transfer.debited`, `transfer.credited`,
`transfer.declined`, `transfer.completed`, `transfer.idempotent_replay`,
`transfer.conflict`, and the deposit/get-or-create events. Made publicly viewable as
a screen recording of the log stream during a burst run.

**Metrics.** Exposed at `/actuator/prometheus`: request rate and error rate
(`http_server_requests_seconds_count` with `status`/`outcome` tags), latency **p99**
via histogram buckets (`histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))`),
and **domain counters** — `wallet_transfers_completed_total`,
`wallet_transfers_declined_total{reason="insufficient_funds"}`,
`wallet_transfers_idempotent_replays_total`, `wallet_transfers_conflicts_total`.

**Verification.** [`scripts/burst.sh`](../scripts/burst.sh) reproduces all three
graded invariants against the live URL in one command and asserts them (including
zero 5xx under the A→B + B→A contention cross); it passes 8/8 against production.

## Reversal (R3 extension) — how it fits

A refund reuses the exact same primitive with roles swapped: a new transfer row
with its own idempotency key, debit the original recipient (conditional, so it
declines cleanly if they already spent the funds), credit the original sender, all
in one transaction with the same sorted-lock ordering. Reversing twice is stopped
by the reversal's own idempotency key; reversing a non-existent/already-reversed
transfer is a clean 409/decline.

## AI: directed vs decided

I went through the requirements and problem statement, understood it, and designed
the solution myself — the data model, the concurrency approach, and the API shape.
I then directed Claude Code to implement that design; the AI did the typing. Once
the development was done, I had the AI generate the test cases as well, and I tested
the service end to end. Finally I designed the deployment flow using the available
free-tier options.

- **I directed** (I decided the approach, AI typed): the overall design; the
  conditional-`UPDATE` + sorted-`FOR UPDATE` mechanism for conservation and
  no-overdraft; putting idempotency in the same transaction as the money move;
  READ COMMITTED over SERIALIZABLE; money as integer paise end to end; and the
  choice of Java/Spring Boot and the free-tier deployment path.
- **AI decided** (I accepted its output): boilerplate wiring (filters, exception-
  handler shape), the ECS structured-log format, the exact metric names, the
  integration-test scaffolding, and the burst script's mechanics — all of which I
  reviewed and verified end to end.

## Free-tier cost note

**₹0.** One free web service (Render/Railway/Fly.io/Koyeb) + one free managed
Postgres. The image is a small multi-stage build; the app is a single stateless
process. No paid add-ons, no card required.
