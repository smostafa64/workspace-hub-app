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

## Business clarifications (owner-approved, 2026-10-28)

These clarifications are authoritative. They refine requirements 2, 3 and 4; all other document text remains in force.

### C1 — Daylight-saving handling of nonexistent and ambiguous local times
The weekly series is defined by local wall-clock time in `timezone`.
- If a boundary local time (`startsAt` or `endsAt`) does not exist because the clocks spring forward, shift that local time forward by the size of the gap on that date (for example 02:30 becomes 03:30) and keep the occurrence.
- If a boundary local time occurs twice because the clocks fall back, use the first occurrence, i.e. the earlier UTC instant, and keep the occurrence.
- Applying C1 never rejects the request and never skips an occurrence. The ordinary future, duration and overlap validations then apply to the resulting instants.

### C2 — Accepted timezone identifiers
`timezone` must be either an IANA region identifier of the form Area/Location (for example `Europe/Berlin` or `America/New_York`) or the exact identifier `UTC`. Fixed-offset strings such as `+01:00` or `GMT+01:00` and abbreviations such as `CET` are invalid. An invalid `timezone` is rejected with `400` before any booking is created.

### C3 — Outcome when a non-conflict validation fails
Each occurrence is validated independently and in chronological order. If an occurrence fails non-conflict validation (start not in the future, duration outside 15 minutes–8 hours, or resource not active), that occurrence is skipped and every valid occurrence is still created; a skipped occurrence leaves a gap in the series at its index.
The response is `400` and its body reports the seriesId shared by the created occurrences, the occurrence indexes that were persisted, and the occurrence indexes that failed validation. If no occurrence is valid, no reservation persists and the persisted list is empty.
Overlap conflicts are not affected: any conflict still creates none and returns `409` as required by requirement 4.

### C4 — `occurrences` is an integer
`occurrences` must be a whole number in the inclusive range 2–12. Fractional values such as `2.5` or `2.0`, numeric strings, `null` and booleans are invalid and are rejected with `400` before any booking is created.

### C5 — Status for documented input-format violations
Any request that violates the documented input format — a date-time without an offset, a `timezone` that is not valid under C2, or a `frequency` other than `WEEKLY` — is rejected with `400` before any booking is created.

### C6 — Adjacency is not a conflict
Reservations conflict only when their time intervals strictly overlap: one starts before the other ends and ends after the other starts. Two reservations where one ends exactly when the other starts (`end == start`) do not conflict and are both allowed, including between a series occurrence and a one-off reservation. This atomicity, zero-write and 409 rules of requirement 4 apply only to strict overlaps.
