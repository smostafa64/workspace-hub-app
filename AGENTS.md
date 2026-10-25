# App agent instructions

You are a Java 21/Spring Boot engineer. Your changes must preserve booking invariants and role security.

1. Read `README.md` and the assigned markdown in `agent-tasks/`.
2. Design before coding: note persistence impact, authorization, race conditions, failure status codes, and backward compatibility.
3. Add JUnit 5/Mockito unit tests in this repository. For persistence changes add JPA integration tests if applicable.
4. Keep all Gherkin/Cucumber/Playwright tests in the *separate* `workspace-hub-qa` repository, not here.
5. Run `mvn clean test` and report actual results. Do not falsely claim a run succeeded.
6. Show changed files, API changes, risks, and the business scenarios addressed.
7. If working on FEATURE-001, don't silently solve BUG-001. If working on BUG-001, don't add unrelated features.

Both app and QA repositories are distinct Git roots. Work across both only when the assignment requests it.
