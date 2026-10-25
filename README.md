# Workspace Hub — application repository

A **Java 21 + Maven + Spring Boot** starter application for corporate meeting-room, desk and equipment reservations. This repository owns application logic and **unit tests**. The companion `workspace-hub-qa` repository owns **BDD + E2E tests**.

## What works today

- Seeded room, desk and equipment catalog with HQ/floor/capacity metadata.
- Employee booking and cancellation, room availability API, 15 minute–8 hour booking policy.
- Pending approval for specific resources, manager/admin decision endpoint.
- Server-side role enforcement; audit events; basic single-page browser UI.
- Pessimistic resource row lock inside booking transaction to serialize simultaneous writes.
- Local file-based H2 out of the box; PostgreSQL through Docker Compose.

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

## API examples

```bash
curl -u alice:alice123 http://localhost:8080/api/resources
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
| `/api/reservations?all=true` | GET | signed-in; all only for MANAGER/ADMIN | own or all bookings |
| `/api/reservations/{id}/cancel` | POST | owner or MANAGER/ADMIN | cancel |
| `/api/reservations/{id}/decision` | POST | MANAGER/ADMIN | approve/reject pending |
| `/api/audit` | GET | MANAGER/ADMIN | latest 100 audit entries |

Dates use ISO 8601 timestamps with UTC offsets (`Z` is easiest). Response statuses include `201`, `400`, `403`, `404`, and `409`.

## QA split

- This repository: `mvn test` runs JUnit 5 unit tests with Mockito. Tests use fixed clocks and do not need a database.
- The QA repository: Java/Maven Cucumber tests via Rest Assured and Playwright browser E2E tests.
- Start this app on port 8080 before running the companion QA suite.

## Agent exercises

See [`agent-tasks/FEATURE-001-recurring-bookings.md`](agent-tasks/FEATURE-001-recurring-bookings.md) and [`agent-tasks/BUG-001-back-to-back-bookings.md`](agent-tasks/BUG-001-back-to-back-bookings.md).

## Security / deployment caveats

This is a **demo project**, not production-ready. It has **hardcoded HTTP Basic users**, `{noop}` passwords, **CSRF disabled**, no company SSO, no rate limiting, and `hibernate.ddl-auto=update`. Use HTTPS, company identity provider/OIDC, real authorization policies, database migrations, and secret management before deploying internally. The bundled deliberate availability defect is documented as `BUG-001` and **must be fixed before actual employee use**. No emails/integrations are sent yet.
