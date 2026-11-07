package com.example.workspace.service;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration-layer verification for SCN-013#A3/A4, SCN-015#A1/A2, SCN-016#A1 and SCN-022#A1.
 *
 * <p>Every test invokes the real {@link ReservationService} bean in-process against the embedded H2
 * database owned by this test class (approved {@code embedded-h2-unique-db} fixture). Because the
 * test transaction propagation is {@link Propagation#NOT_SUPPORTED}, each service call runs in its
 * own transaction and is allowed to commit; the resulting committed state is then observed through
 * a brand-new JPA {@link EntityManager} created after the service call. No mocks are used and no
 * business rule is reimplemented in the test.</p>
 *
 * <p>The database name is generated once per class and every method owns UUID-coded bookable
 * resources, so no method can observe another method's rows and no fixed identifier is reused
 * across runs. A fixed clock is injected and every requested timestamp is genuinely in the future
 * relative to it. Concurrent scenarios release both requests through one barrier and bound the
 * barrier wait and both future waits; they assert only the frozen text and never a loser status or
 * a liveness guarantee.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class,
        ReservationSeriesIntegrationObligationsTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesIntegrationObligationsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int WEEKLY_OCCURRENCES = 3;
    private static final int IDENTIFIER_SERIES_OCCURRENCES = 4;
    private static final long BARRIER_TIMEOUT_SECONDS = 15;
    private static final long FUTURE_TIMEOUT_SECONDS = 90;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb005_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ReservationService service;

    @Autowired
    private ResourceRepository resources;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void isolatedDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + DATABASE_NAME
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    // ---------------------------------------------------------------------
    // SCN-013#A3
    // ---------------------------------------------------------------------
    @Test
    void scn013ApprovalRequiredRoomPersistsThreePendingRowsSharingOneSeriesId() {
        BookableResource resource = newResource(true);
        assertTrue(resource.isApprovalRequired(),
                "control: the owned room must really require approval before the business call");

        String title = "scn013-pending-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 2, 5, 10, 0, 0, 0, BERLIN).toOffsetDateTime();

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned room must have no reservations before the series call");

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt,
                startsAt.plusHours(1), WEEKLY_OCCURRENCES);
        assertNotNull(view,
                "control: the series request for an approval-required room must complete");

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(WEEKLY_OCCURRENCES, rows.size(),
                "all three occurrences of the approval-required series must be persisted, but saw "
                        + rows.size());
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "the three persisted rows must belong to the requested series, but saw "
                        + rows.stream().map(PersistedRow::title).toList());
        assertTrue(rows.stream().allMatch(row -> row.status() == ReservationStatus.PENDING),
                "every persisted occurrence must be PENDING, but saw "
                        + rows.stream().map(PersistedRow::status).toList());

        UUID seriesId = rows.get(0).seriesId();
        assertNotNull(seriesId, "every persisted occurrence must carry a non-null seriesId");
        for (PersistedRow row : rows) {
            assertNotNull(row.seriesId(),
                    "reservation row " + row.id() + " must persist a non-null seriesId");
            assertEquals(seriesId, row.seriesId(),
                    "reservation row " + row.id() + " must share the identical seriesId, but saw "
                            + row.seriesId());
        }
    }

    // ---------------------------------------------------------------------
    // SCN-013#A4
    // ---------------------------------------------------------------------
    @Test
    void scn013ApprovalRequirementIsHonouredForEveryOccurrenceInsteadOfAutoConfirming() {
        BookableResource approvalRequiredRoom = newResource(true);
        BookableResource controlRoom = newResource(false);
        assertTrue(approvalRequiredRoom.isApprovalRequired(),
                "treatment: the owned room must require approval");
        assertFalse(controlRoom.isApprovalRequired(),
                "control: the nearby room differs only in the approval requirement");

        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 2, 12, 14, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);
        String treatmentTitle = "scn013-approval-" + UUID.randomUUID();
        String controlTitle = "scn013-control-" + UUID.randomUUID();

        createSeries(approvalRequiredRoom.getId(), treatmentTitle, startsAt, endsAt,
                WEEKLY_OCCURRENCES);
        createSeries(controlRoom.getId(), controlTitle, startsAt, endsAt, WEEKLY_OCCURRENCES);

        List<PersistedRow> treatmentRows = readRows(approvalRequiredRoom.getId());
        assertEquals(WEEKLY_OCCURRENCES, treatmentRows.size(),
                "all three occurrences of the approval-required series must be persisted");
        assertTrue(treatmentRows.stream().allMatch(row -> treatmentTitle.equals(row.title())),
                "every observed row must belong to the approval-required series");
        assertTrue(treatmentRows.stream().allMatch(row -> row.status() == ReservationStatus.PENDING),
                "the room's approval requirement must be honoured for each occurrence, but saw "
                        + treatmentRows.stream().map(PersistedRow::status).toList());
        assertTrue(treatmentRows.stream()
                        .noneMatch(row -> row.status() == ReservationStatus.CONFIRMED),
                "the series must not be auto-confirmed for an approval-required room, but saw "
                        + treatmentRows.stream().map(PersistedRow::status).toList());

        // Differential control: the identical request for a room that does not require approval
        // must not produce PENDING occurrences. Without this control a globally PENDING result
        // would silently satisfy the treatment assertion.
        List<PersistedRow> controlRows = readRows(controlRoom.getId());
        assertEquals(WEEKLY_OCCURRENCES, controlRows.size(),
                "control: the identical series request must persist all three occurrences");
        assertTrue(controlRows.stream().allMatch(row -> controlTitle.equals(row.title())),
                "control: every observed row must belong to the control series");
        assertTrue(controlRows.stream().allMatch(row -> row.status() == ReservationStatus.CONFIRMED),
                "control: the room that does not require approval must not produce PENDING rows, "
                        + "but saw " + controlRows.stream().map(PersistedRow::status).toList());
    }

    // ---------------------------------------------------------------------
    // SCN-015#A1, SCN-015#A2
    // ---------------------------------------------------------------------
    @Test
    @Timeout(120)
    void scn015ConcurrentOverlappingSeriesCannotBothSucceedAndTheWinnerIsFullyPersisted() {
        BookableResource resource = newResource(false);
        String titleA = "scn015-series-a-" + UUID.randomUUID();
        String titleB = "scn015-series-b-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 3, 5, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned room must have no reservations before the race");

        List<Outcome> outcomes = runConcurrently(
                () -> createSeries(resource.getId(), titleA, startsAt, endsAt, WEEKLY_OCCURRENCES),
                () -> createSeries(resource.getId(), titleB, startsAt, endsAt, WEEKLY_OCCURRENCES));

        long successCount = outcomes.stream().filter(Outcome::succeeded).count();

        // SCN-015#A1: the two concurrent overlapping series requests do not both succeed.
        assertTrue(successCount <= 1,
                "the two concurrent overlapping series requests must not both succeed, but "
                        + successCount + " succeeded; outcomes: " + describe(outcomes));

        List<PersistedRow> rows = readRows(resource.getId());

        if (successCount == 1) {
            Outcome winnerOutcome = outcomes.stream()
                    .filter(Outcome::succeeded)
                    .findFirst()
                    .orElseThrow();
            ReservationSeriesView winner = (ReservationSeriesView) winnerOutcome.value();
            String winnerTitle = winner.title();
            String loserTitle = winnerTitle.equals(titleA) ? titleB : titleA;
            assertEquals(WEEKLY_OCCURRENCES, winner.occurrences(),
                    "control: each competing request was submitted with three occurrences");

            // SCN-015#A2: the successful request's occurrences are all persisted.
            assertEquals(winner.occurrences(), rows.size(),
                    "the store must hold every occurrence of the successful request, but saw "
                            + rows.size() + " rows; outcomes: " + describe(outcomes));
            assertTrue(rows.stream().allMatch(row -> winnerTitle.equals(row.title())),
                    "every persisted row must belong to the winning series, but saw "
                            + rows.stream().map(PersistedRow::title).toList());
            UUID winnerSeriesId = rows.get(0).seriesId();
            assertNotNull(winnerSeriesId,
                    "the winning request must persist one non-null series identifier");
            assertTrue(rows.stream().allMatch(row -> winnerSeriesId.equals(row.seriesId())),
                    "the winning request's rows must all share its series identifier, but saw "
                            + rows.stream().map(PersistedRow::seriesId).toList());

            // SCN-015#A2: no partial set from the loser exists.
            assertTrue(rows.stream().noneMatch(row -> loserTitle.equals(row.title())),
                    "no partial set from the failed request may exist in the store; outcomes: "
                            + describe(outcomes));
        } else {
            // Neither attempt succeeded, so any row would be a partial set from a losing request.
            assertTrue(rows.isEmpty(),
                    "when neither attempt succeeds no partial set from either request may exist, "
                            + "but saw " + rows.size() + " rows; outcomes: " + describe(outcomes));
        }
    }

    // ---------------------------------------------------------------------
    // SCN-016#A1
    // ---------------------------------------------------------------------
    @Test
    @Timeout(120)
    void scn016ConcurrentOneOffAndRecurringCannotBothSucceedForTheSameRoomAndTime() {
        BookableResource resource = newResource(false);
        String oneOffTitle = "scn016-one-off-" + UUID.randomUUID();
        String seriesTitle = "scn016-recurring-" + UUID.randomUUID();
        OffsetDateTime firstOccurrence = ZonedDateTime
                .of(2030, 4, 9, 11, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime seriesEnd = firstOccurrence.plusHours(1);
        Instant oneOffStart = firstOccurrence.toInstant();
        Instant oneOffEnd = firstOccurrence.plusMinutes(30).toInstant();

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned room must have no reservations before the race");

        List<Outcome> outcomes = runConcurrently(
                () -> service.create(
                        new BookingInput(resource.getId(), oneOffTitle, oneOffStart, oneOffEnd),
                        "qa-one-off-" + UUID.randomUUID()),
                () -> createSeries(resource.getId(), seriesTitle, firstOccurrence, seriesEnd,
                        WEEKLY_OCCURRENCES));

        Outcome oneOff = outcomes.get(0);
        Outcome recurring = outcomes.get(1);

        // SCN-016#A1: the one-off and the recurring request cannot both succeed for the same room
        // and time. Only the frozen mutual exclusion is asserted: no loser status and no liveness.
        assertFalse(oneOff.succeeded() && recurring.succeeded(),
                "the one-off and the recurring request must not both succeed for the same room and "
                        + "time; outcomes: " + describe(outcomes));
    }

    // ---------------------------------------------------------------------
    // SCN-022#A1
    // ---------------------------------------------------------------------
    @Test
    void scn022EverySeriesReservationRowPersistsTheSameStableSeriesIdentifier() {
        BookableResource resource = newResource(false);
        String title = "scn022-series-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 5, 7, 13, 0, 0, 0, BERLIN).toOffsetDateTime();

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned room must have no reservations before the series call");

        createSeries(resource.getId(), title, startsAt, startsAt.plusHours(1),
                IDENTIFIER_SERIES_OCCURRENCES);

        List<PersistedRow> firstRead = readRows(resource.getId());
        assertEquals(IDENTIFIER_SERIES_OCCURRENCES, firstRead.size(),
                "the series request must persist every requested occurrence, but saw "
                        + firstRead.size());
        assertTrue(firstRead.stream().allMatch(row -> title.equals(row.title())),
                "every observed row must belong to the requested series, but saw "
                        + firstRead.stream().map(PersistedRow::title).toList());

        UUID seriesId = firstRead.get(0).seriesId();
        assertNotNull(seriesId,
                "every reservation row belonging to a series must persist a series identifier");
        for (PersistedRow row : firstRead) {
            assertNotNull(row.seriesId(),
                    "reservation row " + row.id() + " must persist a series identifier");
            assertEquals(seriesId, row.seriesId(),
                    "reservation row " + row.id() + " must persist the same series identifier as "
                            + "the other rows of the series");
        }

        // A second, independent EntityManager re-reads the committed rows; a value regenerated per
        // read would not survive this observation.
        List<PersistedRow> secondRead = readRows(resource.getId());
        assertEquals(firstRead.size(), secondRead.size(),
                "the independent re-read must observe the same number of series rows");
        for (int index = 0; index < firstRead.size(); index++) {
            assertEquals(firstRead.get(index).id(), secondRead.get(index).id(),
                    "the independent re-read must observe the same rows in the same order");
            assertEquals(seriesId, secondRead.get(index).seriesId(),
                    "the independent re-read of row " + secondRead.get(index).id()
                            + " must observe the same persisted series identifier");
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource(boolean approvalRequired) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB005 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true,
                approvalRequired);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                               OffsetDateTime endsAt, int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(occurrences, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return service.createSeries(input, "qa-" + UUID.randomUUID());
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedRow> readRows(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title, r.status "
                                    + "from Reservation r where r.resource.id = :resourceId "
                                    + "order by r.startsAt, r.id", Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedRow> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedRow(
                        ((Number) row[0]).longValue(),
                        toInstant(row[1]),
                        toInstant(row[2]),
                        (UUID) row[3],
                        (String) row[4],
                        (ReservationStatus) row[5]));
            }
            return result;
        } finally {
            entityManager.close();
        }
    }

    /** Normalises the JDBC/Hibernate temporal representation of a persisted instant. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        assertNotNull(value, "a persisted reservation must have a non-null timestamp");
        throw new IllegalStateException("unexpected temporal type: " + value.getClass().getName());
    }

    /**
     * Releases both actions through one barrier so the two service transactions really compete,
     * then waits for both with bounded timeouts. A barrier or task-level harness failure is a test
     * error, not a business outcome, and fails the test through {@link Future#get}.
     */
    private static List<Outcome> runConcurrently(Callable<Object> first, Callable<Object> second) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "wb005-race-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        });
        try {
            Callable<Outcome> firstTask = () -> awaitReleaseThenRun(barrier, first);
            Callable<Outcome> secondTask = () -> awaitReleaseThenRun(barrier, second);
            List<Future<Outcome>> futures = new ArrayList<>(2);
            futures.add(executor.submit(firstTask));
            futures.add(executor.submit(secondTask));

            List<Outcome> outcomes = new ArrayList<>(2);
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    fail("a concurrent request did not complete within "
                            + FUTURE_TIMEOUT_SECONDS + " seconds");
                } catch (ExecutionException failure) {
                    fail("a concurrent request task failed unexpectedly: " + failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail("interrupted while awaiting the concurrent requests");
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Outcome awaitReleaseThenRun(CyclicBarrier barrier, Callable<Object> action) {
        try {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception releaseFailure) {
            throw new IllegalStateException(
                    "both concurrent requests could not be released together", releaseFailure);
        }
        try {
            return new Outcome(action.call(), null);
        } catch (RuntimeException businessFailure) {
            return new Outcome(null, businessFailure);
        } catch (Exception checkedFailure) {
            return new Outcome(null, new IllegalStateException(checkedFailure));
        }
    }

    private static String describe(List<Outcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.succeeded()
                        ? "SUCCESS " + outcome.value()
                        : "FAILURE " + outcome.failure().getClass().getName() + ": "
                                + outcome.failure().getMessage())
                .collect(Collectors.joining(" | "));
    }

    private record Outcome(Object value, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    private record PersistedRow(Long id, Instant startsAt, Instant endsAt, UUID seriesId,
                                String title, ReservationStatus status) {
    }
}
