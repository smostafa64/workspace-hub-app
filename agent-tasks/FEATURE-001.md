# FEATURE-001: Independent QA Test Implementation and Execution

**Role:** Senior Software Development Engineer in Test (SDET)  
**Priority:** Critical  
**Task:** Design, implement, and execute tests for recurring meeting-room reservations.

## 1. Objective

FEATURE-001 has been implemented by a separate Developer Agent.

Your job is to independently verify the feature against its original business requirements.

Do not assume the implementation is correct based on the Developer Agent's report or its existing unit tests.

You must design meaningful tests, execute them, investigate failures, and document defects.

## 2. Repository Responsibilities

You have access to:

**workspace-hub-app**
- Read production code and API contracts.
- Create and maintain unit tests.
- Create integration tests where appropriate.
- Do not modify production implementation.

**workspace-hub-qa**
- Create Cucumber/Gherkin BDD scenarios.
- Implement Rest Assured API tests.
- Implement Playwright Java E2E tests.
- Maintain test fixtures and execution documentation.

You must NOT:
- Change production Java source code.
- Fix business logic defects yourself.
- Modify database migrations to make tests pass.
- Weaken assertions to accommodate wrong behavior.
- Disable or delete failing tests without documenting why.
- Treat known BUG-001 as a FEATURE-001 regression.
- Rely solely on tests written by the Developer Agent.

Report confirmed implementation bugs for the Developer Agent to fix.

## 3. Functional Test Requirements

Test the following business behaviors:

### TC-001: Successful Recurring Reservation

Verify that:
- The API accepts a valid weekly reservation request.
- 2–12 occurrences are supported.
- HTTP 201 is returned.
- All occurrences have the same seriesId.
- Each occurrence has the correct room, title, and status.
- The response includes the expected occurrence information.

### TC-002: Input Validation

Test:
- Occurrences less than 2.
- Occurrences greater than 12.
- Missing or invalid timezone.
- Unsupported frequency.
- Missing resourceId.
- Inactive or nonexistent resources.
- Start time in the past.
- End time before start time.
- Duration shorter than 15 minutes.
- Duration longer than 8 hours.
- Missing timestamps or invalid offsets.

Verify appropriate HTTP error responses.

### TC-003: DST and Timezone

Test weekly reservations crossing:

- Spring daylight-saving transition.
- Autumn daylight-saving transition.
- Ambiguous local times.
- Nonexistent local times.
- Different IANA timezones.

Verify:
- Local start clock time remains constant.
- Local end clock time remains constant.
- UTC offsets change correctly.
- Actual elapsed duration is validated per occurrence.
- Earlier-overlap policy is applied.
- Gap handling follows documented behavior.
- Invalid explicit offsets are rejected.

Use deterministic future test dates and avoid reliance on the current system clock where possible.

### TC-004: Atomicity

Create a scenario where the second or third occurrence conflicts with an existing reservation.

Verify:
- API returns 409.
- Response identifies the conflicting occurrence.
- No reservations from the failed series exist in the database.

Also test multiple conflicting occurrences.

### TC-005: Concurrency

Use real database integration tests to simulate:

1. Two simultaneous recurring reservation requests for the same room and times.
2. One recurring reservation competing with a one-time reservation.
3. Two simultaneous one-time reservation requests.

Verify:
- Both overlapping attempts cannot succeed.
- No duplicate active reservations are stored.
- Database state remains consistent.

Use synchronized request starts, proper concurrency assertions, and repeated runs to improve the chance of exposing race conditions.

Do not use Mockito-only tests as proof of database locking correctness.

### TC-006: Approval Workflow

Verify:
- Approval-required rooms create PENDING occurrences.
- Authorized users can approve individual occurrences.
- Unauthorized users cannot approve them.
- Approval does not create overlapping confirmed bookings.

### TC-007: Individual Cancellation

Verify:
- One occurrence can be cancelled.
- Other occurrences remain unchanged.
- All occurrences retain their seriesId.
- Unauthorized cancellation is rejected.

### TC-008: Database Migration

Using PostgreSQL, verify:

- Fresh database initialization.
- Upgrade of a representative legacy schema without Flyway history.
- Upgrade from a database where V1 is already applied.
- Existing reservation records remain intact.
- series_id is nullable.
- Schema constraints and indexes are correct.

Pay special attention to the V1-to-V2 Java-based Flyway repair.

Use isolated disposable databases. Never run destructive migration tests against a company or shared database.

### TC-009: UI E2E

Using Playwright for Java, automate:

1. Login as an employee.
2. Open the reservation screen.
3. Select a meeting room.
4. Choose Weekly Series.
5. Enter valid start/end dates and times.
6. Select Europe/Berlin.
7. Choose three occurrences.
8. Submit the reservation.
9. Verify success.
10. Verify the three reservations and their series membership.

Also cover:
- Timezone behavior across DST.
- Invalid inputs.
- Detailed multi-occurrence conflict messages.
- Existing one-time reservation flow.

### TC-010: Backward Compatibility

Execute existing application and QA regression tests.

Verify:
- One-time reservations still work.
- Availability checks still work.
- Existing approval and cancellation behavior is preserved.
- Authorization rules remain enforced.

BUG-001 is a known separate defect. Keep it explicitly identified and isolated rather than silently changing its expected result.

## 4. Test Implementation Rules

Use:
- Java 21.
- Maven.
- JUnit 5.
- Mockito for isolated unit tests.
- Testcontainers with PostgreSQL for database integration testing, where supported.
- Cucumber/Gherkin for BDD.
- Rest Assured for API testing.
- Playwright Java for UI E2E.

Requirements:
- Tests must be deterministic.
- Test data must be isolated.
- No dependency on previous test executions.
- Use clear, meaningful assertions.
- Avoid unnecessary Thread.sleep.
- Use explicit browser waits.
- Keep BDD scenarios readable by business stakeholders.
- Preserve the separation between application unit tests and QA automation.

## 5. Execution

Run application tests:

```bash
cd workspace-hub-app
mvn clean verify
```

Run the separate QA test suites using their configured Maven runners.

Run PostgreSQL integration tests using an isolated database environment.

Run Playwright browser tests against a running application.

If an environment dependency is unavailable, report the limitation explicitly rather than claiming success.

## 6. Defect Reporting

When a test fails:

1. Confirm whether the test expectation matches the business requirement.
2. Investigate the failure.
3. Distinguish a test defect, environment problem, and application defect.
4. Provide reproduction steps.
5. Include expected and actual results.
6. Attach relevant assertion failures, logs, or screenshots.
7. Reference the affected requirement.

Do not modify production code to resolve test failures.

## 7. Deliverables

**A. Test Coverage Matrix**

Map all FEATURE-001 requirements to implemented tests.

**B. New Unit Tests**

List tests added to workspace-hub-app.

**C. BDD Scenarios**

List feature files, scenarios, and results in workspace-hub-qa.

**D. E2E Tests**

List browser journeys and execution results.

**E. Test Execution Report**

Include commands, environment, passed/failed/skipped counts, and evidence.

**F. Defect Report**

For every confirmed defect, provide severity, requirement, reproduction steps, expected/actual behavior, and evidence.

**G. Remaining Risks**

List untested functionality and environment limitations.

**H. Final QA Verdict**

Choose one:
- PASS: All required acceptance criteria verified.
- FAIL: Confirmed application defects violate requirements.
- INCONCLUSIVE: Required verification could not be completed.

## 8. Final Instructions

Implement actual automated tests in both repositories.

Do not stop at proposing scenarios.

Execute the tests wherever possible.

Commit test changes separately in the appropriate repositories.

Do not modify production implementation.

Your responsibility is to independently determine whether FEATURE-001 works correctly.
