# FEATURE-001 — Implement recurring meeting-room bookings

**Type:** New feature | **Priority:** High | **Owner:** AI development agent

## Business request
Employees schedule weekly team meetings. Currently they must manually reserve each week. Add recurring reservations while preserving the existing booking and approval rules.

## User story
As an employee, I want to reserve the same room at the same local clock time every week for up to 12 occurrences, so my weekly meetings are booked in one action.

## Requirements
1. Add `POST /api/reservation-series` taking `resourceId`, `title`, `startsAt`, `endsAt`, `timezone` (IANA ID, e.g. `Europe/Berlin`), `frequency: WEEKLY`, and `occurrences` (2–12). Dates must include an offset.
2. Expand recurrence using local wall-clock time in supplied zone, accounting for daylight saving time. Document how ambiguous/nonexistent local times are handled.
3. Validate all occurrences (future, 15 minutes–8 hours, active resource, overlapping reservations).
4. **Atomic operation**: if any occurrence conflicts, create none and return `409` with conflicting dates/occurrence indexes. Avoid losing atomicity to self-invocation or transaction mistakes.
5. Store a stable series identifier for all bookings. Extend reservation response to include `seriesId` (nullable for one-off bookings). Prefer database migration rather than uncontrolled schema changes.
6. Approval-required rooms create `PENDING` occurrences. Cancellation of an individual occurrence remains possible through the existing endpoint.
7. Prevent competing requests from double-booking a room, including one-off and recurring requests. Use the same resource lock discipline.
8. Add a minimal UI control to reserve a weekly series and display series membership.

## Acceptance criteria
- 3 weekly bookings created at the same room's local start time with distinct UTC instants as appropriate.
- On one conflict (e.g., second occurrence), API returns `409` and **zero** new bookings exist.
- Invalid `occurrences` values return `400`.
- Two concurrent overlapping series/one-off attempts cannot both succeed.
- Unit tests in `workspace-hub-app`; Gherkin BDD plus at least one UI E2E test in `workspace-hub-qa`.
- Existing one-time reservation flow still passes its tests.

## Definition of done
Update API contract/docs, source code and schema, README examples, focused tests in both repos, and a short test execution report. Do **not** mix in the BUG-001 fix unless separately assigned.
