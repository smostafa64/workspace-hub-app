# FEATURE-001 — Independent QA execution plan

**Document role:** Testing and execution instructions, **not** an authoritative source of new business rules.  
**Business authority:** `FEATURE-001-recurring-bookings.md` only.  
**Task configuration:** `FEATURE-001.task.yml`.  
**Project configuration:** `project.yml`.

## Objective and restrictions

Implement and run independently designed tests for recurring meeting-room bookings. Do not accept Developer Agent claims as proof. Do not change application production code, migrations, deployment configuration or business requirements to make tests pass. Do not weaken or remove assertions. Keep the unrelated `BUG-001` distinct.

The Business Architect derives all scenarios and expected outcomes from the business-authority document. The Writer may inspect approved interfaces, test repositories, and automation knowledge to implement those scenarios, **not application production `src/main`**. A separate, post-design-freeze source-impact process may inspect code-change risk categories without defining expected behavior.

Use `workspace-hub-app/src/test` for developer-owned tests and `workspace-hub-qa` for Cucumber, REST Assured, and Playwright Java tests. Both are separate testing subjects: preserve their repository identities and executable test reports.

## Test coverage instructions

The sections below identify coverage work. Where they go beyond the business document, treat them as *risk-derived or proposed checks*, not automatically approved product requirements. Record ambiguous expected results as `ASSUMPTION_REQUIRED` or `REQUIREMENT_REVIEW_REQUIRED`. Never turn a test-plan suggestion into a business oracle.

### TC-001 — Successful weekly series

Verify `POST /api/reservation-series` with valid fields, `WEEKLY`, and counts from 2 through 12 where applicable. Check HTTP 201, exactly the requested number of bookings, consistent non-null `seriesId`, resource/title/status, and correct occurrence data. Confirm results by a supported read interface or direct persistence evidence where required.

### TC-002 — Validation

Exercise invalid occurrence counts (including fractional `2.5`), missing/invalid zone, unsupported frequency, missing resource, inactive resource, past start, invalid timestamp/offset, and duration below 15 minutes or above 8 hours. The business document **explicitly requires HTTP 400 for invalid `occurrences`**. For other invalid inputs it requires validation, but does not universally specify an exact HTTP status; use approved error contracts or record the oracle gap. Verify no unintended records were created when applicable.

### TC-003 — DST and time zones

Test weekly local-clock recurrence across spring and autumn DST transitions and supported IANA zones. Check the same intended local times and the correct instant/offset relationship. The original business document **does not define** how ambiguous autumn times, nonexistent spring times, or conflicting explicit offsets should be resolved. Do not assume an earlier-overlap or gap-shift policy: request an approved business decision before asserting one.

Use dates determined from the test runtime so they remain in the future. Preserve the weekday, local clock time, zone, count, and intended DST boundary; derive expected instants from the approved timezone policy rather than hardcoded stale dates.

### TC-004 — Atomicity and persistence

Seed a conflict at the second, last, or multiple occurrences. Assert HTTP 409, correctly identified conflict dates/indexes, and **zero new bookings** from the rejected series. A response status alone does not establish rollback: inspect persisted records through an approved read interface or isolated DB connection. If unavailable, report this assertion `NEEDS_BINDING`.

### TC-005 — Concurrency

Run concurrent series-versus-series and series-versus-one-off requests with synchronized starts; inspect the real stored result. Verify that competing overlapping requests cannot both succeed and that no duplicate booking is committed. One-off-versus-one-off contention is additional regression risk coverage if the existing business contract establishes the rule. Mockito-only execution cannot prove database locking.

### TC-006 — Approval

For a verified approval-required test room, assert that series occurrences start as `PENDING`. Approval permissions, individual approval operations, and approval conflict rules require existing approved contracts; test them as related regressions **only where the business contract or prior requirements establish expectations**.

### TC-007 — Individual cancellation

Cancel one occurrence through the documented existing endpoint. Verify the other occurrences remain intact with the original series membership. Authorization checks require an approved existing permissions contract.

### TC-008 — Database/schema compatibility

Use disposable PostgreSQL/Testcontainers where available. Verify nullable `series_id`, preservation of existing reservations, schema constraints, and the migration supported by the actual project. Fresh creation and applicable legacy upgrades are valuable migration regression checks. Do not assume a particular Flyway V1-to-V2 repair path exists until the approved migration plan establishes it. Never run destructive migration tests against shared data.

### TC-009 — Browser E2E

Use Playwright Java to complete an employee's real weekly-series booking and verify three occurrences and visible series membership. Reuse **verified** UI selectors or accessible roles/names. Do not invent `data-testid` values. Record absent controls, timezone inputs, error rendering, and membership selectors as separate missing bindings. Also verify the existing one-off browser workflow.

### TC-010 — Existing-flow regression

Run existing application and QA tests that exercise one-time bookings, availability, approval, cancellation, and authorization. Keep the unrelated known `BUG-001` identifiable; it is not a reason to fabricate a PASS or suppress new regressions.

## Binding and fixture prerequisites

Before Writer generation, use the Project Understanding catalog and check:

1. Approved recurring-series API contract or test-visible interface signatures for unit and REST tests.
2. QA fixture creation/cleanup for active non-approval, approval-required, and inactive resources; do not invent permanent resource IDs.
3. Authentication and role fixtures for employee and manager without committing secrets.
4. Durable observation boundary (approved read API / test database adapter) for rollback and concurrency assertions.
5. Existing or newly documented selectors for weekly-series controls, results, and membership display.
6. Maven/JUnit, Cucumber, Rest Assured, Playwright, and isolated Postgres readiness.
7. Test target bound to the full frozen application SHA or an independently observed immutable deployed build identity.

If any prerequisite is unavailable, mark only the affected assertions `NEEDS_BINDING` / `BLOCKED`, continue other executable work, and do not invent an endpoint, production class, selector, fixture, or passing result.

## Execution and evidence

- Compile both repositories with their actual Maven module/runners and run focused suites plus the required regressions. `mvn clean verify` is only an example until the app's actual modules and test profiles are confirmed.
- Keep authoritative native JUnit/Cucumber reports and browser evidence, tied to exact native testcase names and plan/binding identities; do not infer runtime evidence from compilation.
- Every independently verifiable frozen assertion retains its stable assertion ID. Missing database verification cannot be covered by an HTTP response.
- Run deterministic manifest, test-result attribution, QC, and release gates. A supplementary/manual run does not become formal evidence automatically.
- Distinguish confirmed application defects, test defects, environment blockers, ambiguous requirements, and unverified build identity.
- Track tokens and LLM calls by phase and step from task initialization. Token budget targets are advisory and must never terminate the task.

## Known observations from the prior attempt (not new business requirements)

- `occurrences: 2.5` reportedly produced HTTP 201 and two bookings, while the business acceptance criterion requires HTTP 400 for invalid occurrence values. Record as an application defect *signal* until reproduced against an identified candidate build.
- Unit-series interface, inactive-resource fixture, weekly-series UI selectors, certain status observations, and a pre-existing QA glue compilation issue blocked obligations. Recheck after configuring the real environment; do not copy prior `BLOCKED` or `PASS` as current runtime evidence.

## Deliverables

Provide frozen requirement/scenario/assertion traceability, implemented tests and locations, manifest obligations (including missing bindings), actual execution commands/native evidence, confirmed defects with reproductions, coverage gaps, candidate identity, regression results, phase/step token usage, and final QA `PASS` / `FAIL` / `BLOCKED` status according to the agent's gates. Do not push or deploy without explicit authorization.
