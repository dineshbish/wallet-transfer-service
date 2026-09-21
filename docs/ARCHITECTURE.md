# Architecture & Flow

End-to-end design of the Wallet & P2P Transfer service. Diagrams are Mermaid and
render directly on GitHub.

## 1. System / deployment architecture

How the pieces fit together when deployed on a free host with managed Postgres.

```mermaid
flowchart TB
    client["Client<br/>(curl / burst.sh)<br/>Authorization: Bearer token"]

    subgraph host["Free host container (Render / Railway / Fly.io / Koyeb)"]
        direction TB
        subgraph app["Spring Boot app (stateless)"]
            direction TB
            f1["CorrelationIdFilter<br/>(order 10) — assigns X-Correlation-Id, MDC"]
            f2["BearerAuthFilter<br/>(order 20) — token → AuthContext"]
            subgraph web["Controllers"]
                wc["WalletController<br/>/wallets, /wallets/{id}, /deposit"]
                tc["TransferController<br/>/transfers, /transfers/{id}"]
            end
            subgraph svc["Services (@Transactional)"]
                ws["WalletService"]
                ts["TransferService"]
            end
            subgraph repo["Repositories (NamedParameterJdbcTemplate — raw SQL)"]
                wr["WalletRepository"]
                tr["TransferRepository"]
            end
            fly["Flyway<br/>(runs migrations on startup)"]
            act["Actuator<br/>/health, /prometheus"]
        end
    end

    pg[("Managed Postgres<br/>wallets · transfers · deposits<br/>flyway_schema_history")]

    prom["Prometheus / dashboard<br/>(scrapes /prometheus)"]
    logs["Log drain<br/>(structured JSON, correlation_id)"]

    client -->|HTTPS| f1 --> f2 --> web
    wc --> ws
    tc --> ts
    ws --> wr
    ts --> tr
    ts --> wr
    wr --> pg
    tr --> pg
    fly -->|"on boot, before serving"| pg
    act -.->|metrics| prom
    app -.->|stdout JSON| logs
```

Everything correct lives in the database (constraints + locks); the app layer is
stateless and can be scaled horizontally.

## 2. Transfer flow — exactly-once, conservation, no-overdraft

The full path of a `POST /transfers`, all inside one READ COMMITTED transaction.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant F as Filters (correlation id + auth)
    participant TC as TransferController
    participant TS as TransferService (@Transactional)
    participant DB as Postgres

    C->>F: POST /transfers {from,to,amount_paise,idempotency_key} + Bearer
    F->>TC: correlation id in MDC, caller in AuthContext (401 if no token)
    TC->>TS: transfer(caller, from, to, amount, key)

    Note over TS,DB: BEGIN transaction
    TS->>TS: validate from ≠ to, amount > 0
    TS->>DB: SELECT wallets from, to (exist?)
    alt a wallet is missing
        TS-->>C: 404 Not Found
    end
    alt from wallet not owned by caller
        TS-->>C: 403 Forbidden (cannot move money out of another's wallet)
    end

    TS->>DB: lock from & to FOR UPDATE, one row per stmt,<br/>ascending id order (deterministic → no deadlock),<br/>before the INSERT so the FK KEY SHARE is subsumed
    TS->>DB: INSERT transfer PENDING<br/>ON CONFLICT (idempotency_key) DO NOTHING
    alt rows inserted = 0 (key already exists)
        TS->>DB: SELECT existing transfer by key
        alt request_hash differs
            TS-->>C: 409 Conflict (same key, different body)
        else same body
            TS-->>C: 200 OK — original result (idempotent replay)
        end
    else rows inserted = 1 (we own it)
        TS->>DB: UPDATE ... balance = balance - amt WHERE id=from AND balance >= amt
        alt rows affected = 0 (insufficient funds)
            TS->>DB: UPDATE transfer SET status = DECLINED
            Note over TS,DB: COMMIT
            TS-->>C: 200 OK status=DECLINED
        else rows affected = 1 (debited)
            TS->>DB: UPDATE ... balance = balance + amt WHERE id=to
            TS->>DB: UPDATE transfer SET status = COMPLETED
            Note over TS,DB: COMMIT
            TS-->>C: 200 OK status=COMPLETED
        end
    end
```

## 3. Idempotency decision (why a retry storm is safe)

What each of K concurrent requests with the *same* key does.

```mermaid
flowchart TD
    start(["POST /transfers with idempotency_key"]) --> ins["INSERT PENDING<br/>ON CONFLICT DO NOTHING"]
    ins --> won{"Did I win<br/>the insert?"}

    won -->|"yes (1 row)"| move["Lock wallets in id order<br/>→ conditional debit → credit<br/>→ mark COMPLETED/DECLINED"]
    move --> commit["COMMIT"]
    commit --> orig["Return this result"]

    won -->|"no (0 rows)"| wait["Wait on unique index<br/>until winner commits"]
    wait --> read["SELECT existing transfer"]
    read --> hash{"request_hash<br/>matches?"}
    hash -->|yes| replay["Return original result<br/>(no second debit)"]
    hash -->|no| conflict["409 Conflict"]

    classDef good fill:#e6f4ea,stroke:#34a853;
    classDef warn fill:#fce8e6,stroke:#ea4335;
    class orig,replay good;
    class conflict warn;
```

The unique constraint on `idempotency_key` means exactly one request wins; every
other request reads the winner's committed result — so money moves once.

## 4. Get-or-create wallet (race-free)

```mermaid
flowchart LR
    a(["POST /wallets (Bearer user)"]) --> b["INSERT INTO wallets(user_id)<br/>ON CONFLICT (user_id) DO NOTHING"]
    b --> c["SELECT wallet WHERE user_id = ?"]
    c --> d(["Return the one wallet"])
```

The `UNIQUE(user_id)` constraint + `ON CONFLICT` means 50 concurrent calls for a
brand-new user still produce exactly one wallet.

## 5. Startup sequence (containers + migrations)

What `docker compose up` does, and why the app is correct the instant it is healthy.

```mermaid
sequenceDiagram
    autonumber
    participant U as docker compose up
    participant DB as Postgres container
    participant APP as App container

    U->>DB: start
    DB-->>U: healthcheck pg_isready → healthy
    U->>APP: start (depends_on: db healthy)
    APP->>DB: connect (DATABASE_URL)
    APP->>DB: Flyway migrate — apply V1, V2, V3 in order
    DB-->>APP: schema ready, recorded in flyway_schema_history
    APP->>APP: start web server
    APP-->>U: /actuator/health → UP (liveness + readiness)
    Note over APP,DB: Now serving — schema guaranteed to exist
```

## Data model (entity relationships)

```mermaid
erDiagram
    WALLETS ||--o{ TRANSFERS : "from_wallet_id"
    WALLETS ||--o{ TRANSFERS : "to_wallet_id"
    WALLETS ||--o{ DEPOSITS  : "wallet_id"

    WALLETS {
        uuid id PK
        varchar user_id UK "UNIQUE — race-free get-or-create"
        bigint balance_paise "CHECK >= 0 — no overdraft"
        timestamptz created_at
        timestamptz updated_at
    }
    TRANSFERS {
        uuid id PK
        varchar idempotency_key UK "UNIQUE — exactly-once"
        uuid from_wallet_id FK
        uuid to_wallet_id FK
        bigint amount_paise "CHECK > 0"
        varchar status "PENDING / COMPLETED / DECLINED"
        char request_hash "SHA-256(from,to,amount) — 409 on mismatch"
        timestamptz created_at
    }
    DEPOSITS {
        uuid id PK
        varchar idempotency_key UK "UNIQUE — no double top-up"
        uuid wallet_id FK
        bigint amount_paise "CHECK > 0"
        timestamptz created_at
    }
```
