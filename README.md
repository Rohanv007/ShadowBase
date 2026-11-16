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

## Four-week development roadmap

- **Week 1 — Setup and design:** Establish the repository, document the
  migration workflow, and create the baseline live-table schema.
- **Week 2 — Schema planning:** Implement schema-diff parsing and shadow-table
  DDL generation, with tests for supported changes.
- **Week 3 — Data synchronization:** Implement dual-write synchronization and
  a restartable, throttled chunked backfill.
- **Week 4 — Safe completion:** Add consistency checks, atomic cutover and
  rollback handling, then exercise the workflow under concurrent load.

## Current status

Week 1 starter files are in place. The migration engine and the later roadmap
items are not implemented yet.
