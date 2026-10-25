# BUG-001 — Back-to-back time slots are incorrectly rejected

**Type:** Bug fix | **Priority:** High | **Owner:** AI development agent

## Reproduction
1. Run the application and use an active resource (such as `ALPHA`).
2. Alice creates a confirmed booking from **10:00 to 11:00 UTC** on a future day.
3. Bob creates another booking for the **same resource from 11:00 to 12:00 UTC**.
4. Current behavior: HTTP `409` conflict. **Expected: HTTP `201`**, because the intervals do not overlap.

## Business invariant
Bookings use **half-open intervals** `[startsAt, endsAt)`. Conflict exists if and only if `existing.startsAt < requested.endsAt` AND `existing.endsAt > requested.startsAt`. Intervals with only touching boundaries must not conflict. Both `PENDING` and `CONFIRMED` block availability; `CANCELLED` and `REJECTED` do not.

## Agent instructions
- Find the underlying source of false conflicts; fix the query and any related logic.
- Introduce a repository/integration regression test using H2/PostgreSQL and a service unit test where useful.
- In `workspace-hub-qa`, remove `@known-bug` from the adjacent-slot BDD scenario and add the reverse boundary case.
- Add E2E coverage of an adjacent booking if reliable with UI.
- Ensure actual overlaps still reject with `409`, cancellation frees the time, and simultaneous booking safety still holds.

## Definition of done
Existing app and QA tests pass, new regression tests pass, and a brief root-cause explanation is supplied. No unrelated changes.
