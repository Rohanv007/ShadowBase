# ShadowBase

ShadowBase is a zero-downtime schema migration sandbox. It is intended to
demonstrate how a database table can be transformed while an application keeps
reading and writing to the live table.

## Problem

On a large, actively used table, a conventional `ALTER TABLE` can require a
long-running lock or table rewrite. Reads and writes may queue behind that
operation, producing latency spikes or downtime. ShadowBase explores a staged
migration that keeps the original table available until a replacement is ready.

## Migration architecture

1. **Shadow Table Provisioning** — Create a shadow table based on the live
   table's structure and apply the requested schema changes to the shadow table.
   The live table remains in service.
2. **Dual-Write Synchronization** — Keep the shadow table current with incoming
   `INSERT`, `UPDATE`, and `DELETE` operations using database triggers or a
   change data capture (CDC) stream.
3. **Non-Blocking Chunked Backfill** — Copy existing rows in bounded batches,
   preferably using keyset pagination. Throttle the worker and make batches
   retryable so the backfill can yield to normal database traffic.
4. **Atomic Cutover and Rollback** — Verify that the shadow table is consistent,
   briefly coordinate writers, and promote it using the database's safest
   atomic rename/swap mechanism. Retain the former table until the new table has
   been validated so a rollback remains possible. Exact locking and transactional
   guarantees are database-specific and must be verified for the chosen engine.

## Planned project structure

```text
ShadowBase/
├── src/
│   ├── config/       # Database connection and sandbox configuration
│   ├── core/         # Schema diffing and shadow DDL generation
│   ├── sync/         # Trigger/CDC dual-write synchronization
│   ├── backfill/     # Throttled, restartable chunked copy worker
│   └── cutover/      # Validation, atomic promotion, and rollback
├── sql/
│   └── init_schema.sql
├── tests/            # Migration correctness and concurrent-load tests
└── README.md
```

The layout is a plan for upcoming development; the initial deliverables are the
documentation and baseline SQL schema.

## Getting started

The Week 1 sample schema targets PostgreSQL 14 or newer. Install PostgreSQL and
its command-line client (`psql`), then create a local database and apply the
baseline tables:

```bash
git clone https://github.com/Rohanv007/ShadowBase.git
cd ShadowBase
git checkout rohan
createdb shadowbase_dev
psql -d shadowbase_dev -f sql/init_schema.sql
```

The SQL creates `users` and `orders` as baseline live tables for later migration
simulations. Configure any future local credentials in a `.env` file; it is
excluded from Git. Do not put credentials in committed files.

## Java application

The Java 25+ application uses Maven and HikariCP to connect to PostgreSQL. Copy
`src/main/resources/application.properties.example` to
`src/main/resources/application.properties` and set your database credentials.
The local properties file is ignored by Git. Alternatively, configure
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `MIGRATION_BATCH_SIZE` environment
variables; environment variables take precedence over the properties file.

Run the connection health check and startup entry point with:

```bash
mvn compile exec:java -Dexec.mainClass=com.shadowbase.ShadowBaseApplication
```

Run the migration pipeline from the command line with an `ALTER TABLE` statement:

```bash
mvn compile exec:java -Dexec.args="--migrate ALTER TABLE users ADD COLUMN phone VARCHAR(20)"
```

The previous `--provision` option remains an alias. To accept local HTTP
requests, start the server (it binds only to `127.0.0.1`):

```bash
mvn compile exec:java -Dexec.args="--server 8080"
curl.exe -X POST http://127.0.0.1:8080/migrations `
  -H "Content-Type: text/plain" `
  --data "ALTER TABLE users ADD COLUMN phone VARCHAR(20)"
```

`GET /health` returns the process health status. `POST /migrations` accepts one
supported `ALTER TABLE ... ADD COLUMN ...` statement as UTF-8 plain text and runs
the shadow provisioning, dual-write, backfill, and consistency verification
stages. The HTTP service does not perform table cutover.
Each migration also reports its lifecycle phase, total duration, backfill
throughput, verification mismatches, and cutover pause. Because this pipeline
currently stops after verification, the cutover pause is reported as not
measured until an atomic cutover operation is implemented.

To run a concurrent write workload during migration, use `--simulate` with a
total write rate and worker count:

```bash
mvn compile exec:java -Dexec.args="--simulate 20 4 ALTER TABLE users ADD COLUMN phone VARCHAR(20)"
```

The simulator continuously inserts and updates the migration's live table at
the requested aggregate rate and stops after the migration completes or fails.
Its built-in workload targets the `users` table shape from `sql/init_schema.sql`
(`id`, `username`, and `email`). Migration metrics include successful inserts,
successful updates, and failed write attempts; an update against an empty table
is counted as a failed write.

The default JDBC URL is `jdbc:postgresql://localhost:5432/shadowbase`; the
example configuration sets a default migration batch size of 1000.

## PostgreSQL integration and benchmark harnesses

The concurrency harness creates a temporary PostgreSQL table, runs concurrent
readers and INSERT/UPDATE/DELETE writers during migration, and verifies that the
live and shadow rows remain consistent afterward. Configure `DB_URL`,
`DB_USERNAME`, and `DB_PASSWORD`, then run:

```bash
mvn test-compile exec:java -Dexec.classpathScope=test "-Dexec.mainClass=com.shadowbase.integration.PostgresConcurrentMigrationIntegrationTest"
```

The rename benchmark reports min, median, and p95 latency for PostgreSQL's
transactional table-lock-and-rename sequence over a 50,000-row test table:

```bash
mvn test-compile exec:java -Dexec.classpathScope=test "-Dexec.mainClass=com.shadowbase.integration.PostgresRenameCutoverBenchmark"
```

This benchmark measures the database rename sequence directly; it is not a
benchmark of a `CutoverService`. The current source tree does not yet include a
production cutover/rollback service, so cutover latency under application
traffic remains to be measured against that implementation.

## Current status

The Java application includes safe `ALTER TABLE ADD COLUMN` parsing, shadow
table provisioning, PostgreSQL dual-write triggers, keyset-paginated backfill,
and row/checksum verification. CLI and localhost HTTP triggers are available.
PostgreSQL-backed concurrent traffic and rename-latency harnesses are provided
as opt-in test entry points. Transactional promotion, sequence synchronization,
and rollback are not currently present in this source tree.
