# Independent FEATURE-001 application tests

Only test sources and test dependencies change. Production Java, UI assets, application configuration, and migrations remain unchanged.

## Design

- A fixed clock and explicit UTC expectations test recurrence independently of the developer's tests. Berlin spring/autumn, New York, Tehran, and Lord Howe cover one-hour, zero, and half-hour offset changes.
- `SeriesDatabaseContract` runs through the actual Spring transactional service and repositories. Each invocation creates a unique room and removes its own bookings. No Mockito participates in database locking tests.
- Three race patterns run ten times each using a three-party barrier, bounded futures, exactly one successful writer, one conflict, and persisted row/overlap assertions.
- Conflict tests inspect persisted rows and audit counts. A temporary check constraint rejects the second insert to prove the first booking and audit really roll back. The constraint exists only in the disposable test database and is removed in `finally`.
- Approval, cancellation, and availability tests retain existing status and authorization behavior. HTTP role enforcement is tested in the separate QA repository.
- H2 and PostgreSQL run the same contract. Testcontainers creates its own PostgreSQL 16 database; no external database URL is accepted. Missing Docker produces explicit skipped tests, never a PostgreSQL pass.
- Migration tests use a new random schema per case inside the disposable container. They exercise fresh V1-to-V2 repair, existing V1 history/checksums, a representative populated legacy schema without history, and safe failure for an incomplete populated V1 table. They check primary/foreign keys, required columns, resource code uniqueness, exact index column order, nullable UUID series IDs, and preserved reservation values.

## New tests

| File | Coverage |
| --- | --- |
| `src/test/java/com/example/workspace/service/IndependentSeriesTest.java` | 29 independently designed Mockito/JUnit invocations: counts 2–12, exact membership/times, five zones/transitions, gap and overlap policies, later invalid durations, explicit offsets, IANA-only validation, conflict indexes/no writes |
| `src/test/java/com/example/workspace/service/SeriesDatabaseContract.java` | Shared database contract: atomicity, mid-write rollback, cancellation, pending approval, availability, three repeated transaction races |
| `src/test/java/com/example/workspace/service/SeriesH2IntegrationTest.java` | Executes the database contract with isolated in-memory H2 and a fixed clock |
| `src/test/java/com/example/workspace/service/SeriesPostgresIntegrationTest.java` | Executes the same contract against Testcontainers PostgreSQL when Docker exists |
| `src/test/java/com/example/workspace/service/PostgresMigrationTest.java` | Four PostgreSQL migration cases |

Spring Boot manages Testcontainers versions. The redundant legacy `hamcrest-core` shim is excluded because `spring-boot-starter-test` already provides the complete `hamcrest` library; no assertion functionality is removed.

## Execution

Use Java 21, then run `mvn clean verify`. Both database variants and migrations are discovered by Surefire. For PostgreSQL-only verification with Docker running:

```powershell
mvn '-Dtest=SeriesPostgresIntegrationTest,PostgresMigrationTest' test
```

Check skipped counts: a green Maven exit with missing Docker does not verify PostgreSQL acceptance criteria. IANA validation assertions intentionally remain failing while the confirmed implementation defect is open. BUG-001 is unchanged and belongs to its separate QA diagnostic runner.

Actual execution counts, the complete coverage matrix, defect reproduction, and retained evidence are in the sibling QA repository's `docs/FEATURE-001-QA-REPORT.md`.
