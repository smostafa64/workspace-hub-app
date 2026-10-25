# Workspace Hub — application repository

A **Java 21 + Maven + Spring Boot** starter application for corporate meeting-room, desk and equipment reservations. This repository owns application logic and **unit tests**. The companion `workspace-hub-qa` repository contains browser and API tests.

## What works today

- Seeded room, desk and equipment catalog with HQ/floor/capacity metadata.
- Employee booking and cancellation, room availability API, 15 minute–8 hour booking policy.
- Pending approval for specific resources, manager/admin decision endpoint.
- Server-side role enforcement; audit events; basic single-page browser UI.
- Pessimistic resource row lock inside booking transaction to serialize simultaneous writes.
- Local file-based H2 out of the box; PostgreSQL through Docker Compose.
- Weekly recurring reservations with timezone-aware expansion and atomic conflict handling.

## Requirements

- JDK 21+, Maven 3.9+.

## Run immediately (H2)

```bash
mvn clean test
mvn spring-boot:run
```

Visit **http://localhost:8080/**. Your browser asks for an HTTP Basic username and password:

| User | Password | Role |
|------|----------|------|
| alice | alice123 | EMPLOYEE |
| bob | bob123 | EMPLOYEE |
| manager | manager123 | MANAGER |
| admin | admin123 | ADMIN |

Demo resources: `ALPHA` (room), `BOARD` (room requiring approval), `DESK-01`, `PROJECTOR-01`, `BETA` (disabled).

## Run with PostgreSQL

```bash
docker compose up -d postgres
export DB_URL=jdbc:postgresql://localhost:5432/workspacehub
export DB_DRIVER=org.postgresql.Driver
export DB_USER=workspace
export DB_PASSWORD=local_only_change_me
mvn spring-boot:run
```

`DB_*` values configure the Spring DataSource. The default file-based H2 path is `./workspacehub` (data persists across restarts).

### Database migration strategy

Flyway owns the schema and Hibernate schema updates remain disabled. The published
`V1__add_series_id_to_reservations.sql` migration is intentionally unchanged so databases that
already recorded it do not encounter a checksum mismatch. Migration V2 repairs a fresh database
after V1 by creating the complete application schema and constraints, and it upgrades a database
that already ran V1 by adding every missing reservation column without dropping data.

Pre-feature databases were created by Hibernate and have no Flyway history. With
`baseline-on-migrate` enabled at version 1, Flyway records that existing schema as the legacy
baseline and then applies V2. For a valid legacy schema, V2 only adds the nullable `series_id`
column and its index; existing reservation rows, primary keys, foreign keys, and time indexes are
preserved. If the broken V1 table somehow contains rows despite lacking the columns needed to
represent a reservation, V2 stops with an explicit error instead of discarding or inventing data.

## API examples

```bash
curl -u alice:alice123 http://localhost:8080/api/resources
curl -u alice:alice123 http://localhost:8080/api/reservations
curl -u alice:alice123 -X POST http://localhost:8080/api/reservation-series \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceId": 1,
    "title": "Weekly Engineering Meeting",
    "startsAt": "2026-11-02T10:00:00+01:00",
    "endsAt": "2026-11-02T11:00:00+01:00",
    "timezone": "Europe/Berlin",
    "frequency": "WEEKLY",
    "occurrences": 6
  }'
curl -u alice:alice123 http://localhost:8080/api/reservations
curl -u alice:alice123 -X POST http://localhost:8080/api/reservations \
  -H 'Content-Type: application/json' \
  -d '{"resourceId":1,"title":"Sprint review","startsAt":"2030-06-20T10:00:00Z","endsAt":"2030-06-20T11:00:00Z"}'
curl -u alice:alice123 -X POST http://localhost:8080/api/reservations/1/cancel
curl -u manager:manager123 -X POST http://localhost:8080/api/reservations/2/decision \
  -H 'Content-Type: application/json' -d '{"approved":true}'
```

> The sample future date must be adjusted if running after June 2030.

| Endpoint | Verb | Role | Purpose |
|---|---|---|---|
| `/api/resources` | GET | any signed-in | list all resources |
| `/api/resources` | POST | ADMIN | create resource |
| `/api/resources/{id}` | PUT | ADMIN | edit resource; code immutable |
| `/api/resources/{id}/availability?start=...&end=...` | GET | any signed-in | availability check |
| `/api/reservations` | POST | any signed-in | book resource |
| `/api/reservation-series` | POST | any signed-in | book a weekly recurring series |
| `/api/reservations?all=true` | GET | signed-in; all only for MANAGER/ADMIN | own or all bookings |
| `/api/reservations/{id}/cancel` | POST | owner or MANAGER/ADMIN | cancel |
| `/api/reservations/{id}/decision` | POST | MANAGER/ADMIN | approve/reject pending |
| `/api/audit` | GET | MANAGER/ADMIN | latest 100 audit entries |

## Recurring reservation series behavior

Recurring series expand in the supplied `timezone` using local wall-clock times. Each weekly occurrence keeps the same local start and end time in that timezone, even when daylight-saving transitions shift the UTC offset. The implementation uses Java `java.time` APIs instead of adding 168 hours to a UTC instant.

The timezone policy is deterministic:

- If a local time is ambiguous during a fall-back transition, the earlier valid instant is selected.
- If a local time is nonexistent during a spring-forward transition, the time is shifted forward by the DST gap duration. Its intended local clock time is retained for later weekly occurrences.
- `startsAt` and `endsAt` must resolve to the same instant as this policy in the supplied timezone. A later overlap offset is rejected rather than silently changed; a gap input uses the pre-transition offset and is normalized forward.

The browser applies the same rules without assuming its own timezone: it resolves each
`datetime-local` value in the selected IANA timezone and submits an ISO 8601 value with the
corresponding offset. Conflict responses are rendered as occurrence-specific local date/time
ranges, and the UI reports that no reservations were created only for an atomic series conflict.

A conflict for any occurrence returns HTTP `409` with a `RESERVATION_CONFLICT` payload like:

```json
{
  "code": "RESERVATION_CONFLICT",
  "message": "One or more occurrences conflict with existing reservations",
  "conflicts": [
    {
      "occurrenceIndex": 2,
      "startsAt": "2026-11-09T10:00:00+01:00",
      "endsAt": "2026-11-09T11:00:00+01:00"
    }
  ]
}
```

Dates use ISO 8601 timestamps with UTC offsets (`Z` is easiest). Response statuses include `201`, `400`, `403`, `404`, `409`.

## QA split

- This repository: `mvn test` runs JUnit 5 unit tests with Mockito. Tests use fixed clocks and do not need a database.
- The QA repository: Java/Maven Cucumber tests via Rest Assured and Playwright browser E2E tests.
- Start this app on port 8080 before running the companion QA suite.

## Agent exercises

See [`agent-tasks/FEATURE-001-recurring-bookings.md`](agent-tasks/FEATURE-001-recurring-bookings.md) and [`agent-tasks/BUG-001-back-to-back-bookings.md`](agent-tasks/BUG-001-back-to-back-bookings.md).

## Security / deployment caveats

This is a **demo project**, not production-ready. It has **hardcoded HTTP Basic users**, `{noop}` passwords, **CSRF disabled**, no company SSO, no rate limiting, and `hibernate.ddl-auto=none` with Flyway migrations for schema changes. Keep the app behind trusted internal access for demo use.
