package com.example.workspace.service;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Database-layer verification for the approval-required, cancellation and concurrent series
 * obligations (SCN-013 to SCN-016).
 *
 * <p>Every assertion observes the committed relational store through a brand-new JPA
 * {@link EntityManager} created after the service transaction has completed. The embedded H2
 * database name is generated once per class and the schema is derived from the application entity
 * mappings, per the approved {@code embedded-h2-unique-db} fixture contract. Each test method owns
 * a UUID-coded bookable resource, so no method can observe another method's rows, and no fixed
 * calendar slot or fixed identifier is reused across runs.</p>
 *
 * <p>The concurrency tests release two requests through a shared {@link CyclicBarrier} and bound
 * both the barrier wait and the future wait; they assert only the frozen guarantees (no
 * double-booking and, where the frozen text names the winning request, its committed
 * occurrences).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class, ReservationSeriesStoreContractTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesStoreContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb002_" + UUID.randomUUID().toString().replace("-", "");

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
                "control: the owned resource must really require approval before the business call");

        String title = "approval-series-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime.of(2030, 2, 5, 10, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt, endsAt, 3,
                "qa-" + UUID.randomUUID());
        assertNotNull(view, "control: the series request for an approval-required room must complete");

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(3, rows.size(),
                "all three occurrences of the approval-required series must be persisted");
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "the three persisted rows must belong to the requested series");
        assertTrue(rows.stream().allMatch(row -> row.status() == ReservationStatus.PENDING),
                "every persisted occurrence of an approval-required room must be PENDING, but saw "
                        + rows.stream().map(PersistedReservation::status).toList());

        UUID seriesId = rows.get(0).seriesId();
        assertNotNull(seriesId, "every persisted occurrence must carry a non-null seriesId");
        assertTrue(rows.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                "all three persisted occurrences must share the identical seriesId, but saw "
                        + rows.stream().map(PersistedReservation::seriesId).toList());
    }

    // ---------------------------------------------------------------------
    // SCN-014#A2
    // ---------------------------------------------------------------------
    @Test
    void scn014CancellingOneOccurrenceLeavesTwoBookedRowsWithTheSameSeriesId() {
        BookableResource resource = newResource(false);
        assertFalse(resource.isApprovalRequired(),
                "control: the owned resource must not require approval so the series is confirmed");

        String title = "cancel-series-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime.of(2030, 2, 12, 14, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);
        String actor = "qa-owner-" + UUID.randomUUID();

        createSeries(resource.getId(), title, startsAt, endsAt, 3, actor);

        List<PersistedReservation> beforeCancel = persistedReservations(resource.getId());
        assertEquals(3, beforeCancel.size(),
                "precondition: the three-occurrence series must be persisted before cancelling");
        assertTrue(beforeCancel.stream().allMatch(row -> row.status() == ReservationStatus.CONFIRMED),
                "precondition: the series must be confirmed before cancelling one occurrence");

        PersistedReservation second = beforeCancel.get(1);
        assertNotNull(second.startsAt(), "the second occurrence must have a persisted start instant");

        String owner = second.bookedBy();
        assertNotNull(owner, "the second occurrence must record its owner so the cancel is authorized");
        ReservationView cancelled = service.cancel(second.id(),
                new UsernamePasswordAuthenticationToken(owner, "", List.of()));
        assertNotNull(cancelled, "control: the existing cancellation flow must complete for the owner");

        List<PersistedReservation> afterCancel = persistedReservations(resource.getId());
        assertEquals(3, afterCancel.size(),
                "cancelling one occurrence must not remove any row of the series");

        List<PersistedReservation> cancelledRows = afterCancel.stream()
                .filter(row -> row.status() == ReservationStatus.CANCELLED)
                .toList();
        assertEquals(1, cancelledRows.size(),
                "only the cancelled occurrence may leave the active state, exactly one row was CANCELLED");
        assertEquals(second.id(), cancelledRows.get(0).id(),
                "the cancelled row must be exactly the second occurrence that was cancelled");

        List<PersistedReservation> remaining = afterCancel.stream()
                .filter(row -> !row.id().equals(second.id()))
                .toList();
        assertEquals(2, remaining.size(), "the other two occurrences must still be persisted");
        assertTrue(remaining.stream().allMatch(row -> row.status() == ReservationStatus.CONFIRMED),
                "the other two occurrences must remain booked, but saw "
                        + remaining.stream().map(PersistedReservation::status).toList());

        UUID seriesId = afterCancel.get(0).seriesId();
        assertNotNull(seriesId, "the series identifier must survive the single-occurrence cancellation");
        assertTrue(afterCancel.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                "all remaining occurrences must keep the identical seriesId after the cancellation");
    }

    // ---------------------------------------------------------------------
    // SCN-015#A2, SCN-015#A3
    // ---------------------------------------------------------------------
    @Test
    @Timeout(120)
    void scn015ConcurrentOverlappingSeriesPersistOnlyTheWinnersOccurrences() {
        BookableResource resource = newResource(false);
        String titleA = "concurrent-series-a-" + UUID.randomUUID();
        String titleB = "concurrent-series-b-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime.of(2030, 3, 5, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        List<Outcome> outcomes = runConcurrently(
                () -> createSeries(resource.getId(), titleA, startsAt, endsAt, 3, "qa-a-" + UUID.randomUUID()),
                () -> createSeries(resource.getId(), titleB, startsAt, endsAt, 3, "qa-b-" + UUID.randomUUID()));

        long successCount = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(1, successCount,
                "exactly one of the two concurrent overlapping series requests must succeed so the "
                        + "winner's committed occurrences can be observed (A1 guarantees at most one); "
                        + "outcomes: " + describe(outcomes));

        Outcome winnerOutcome = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow();
        assertTrue(winnerOutcome.value() instanceof ReservationSeriesView,
                "the winning request must return the series view, got " + winnerOutcome.value());
        ReservationSeriesView winner = (ReservationSeriesView) winnerOutcome.value();
        String winnerTitle = winner.title();
        String loserTitle = winnerTitle.equals(titleA) ? titleB : titleA;
        assertEquals(3, winner.occurrences(),
                "control: each competing request was submitted with three occurrences");
        assertNotNull(winner.seriesId(),
                "the winning series must carry the single series identifier observed in the store");

        List<PersistedReservation> rows = persistedReservations(resource.getId());

        // SCN-015#A3: total persisted rows equal the winning request's occurrence count.
        assertEquals(winner.occurrences(), rows.size(),
                "the store must hold exactly the winning request's occurrence count, not a merged "
                        + "or partial set; outcomes: " + describe(outcomes));

        // SCN-015#A3: exactly one series identifier for the slot.
        Set<UUID> seriesIds = rows.stream()
                .map(PersistedReservation::seriesId)
                .collect(Collectors.toSet());
        assertEquals(Set.of(winner.seriesId()), seriesIds,
                "the persisted rows must carry exactly the winning series identifier");

        // SCN-015#A2: every occurrence of the successful request is persisted.
        Set<Instant> expectedStarts = weeklyInstants(startsAt, 3);
        Set<Instant> persistedStarts = rows.stream()
                .map(PersistedReservation::startsAt)
                .collect(Collectors.toSet());
        assertEquals(expectedStarts, persistedStarts,
                "all occurrences of the successful request must be persisted at their weekly slots");

        // SCN-015#A2: no partial set from the loser exists.
        assertTrue(rows.stream().noneMatch(row -> loserTitle.equals(row.title())),
                "no row from the failed request may remain in the store; outcomes: " + describe(outcomes));
        assertTrue(rows.stream().allMatch(row -> winnerTitle.equals(row.title())),
                "every persisted row must belong to the winning series, but saw "
                        + rows.stream().map(PersistedReservation::title).toList());
    }

    // ---------------------------------------------------------------------
    // SCN-016#A1
    // ---------------------------------------------------------------------
    @Test
    @Timeout(120)
    void scn016ConcurrentOneOffAndRecurringCannotBothBookTheSameRoomAndTime() {
        BookableResource resource = newResource(false);
        String oneOffTitle = "one-off-" + UUID.randomUUID();
        String seriesTitle = "recurring-" + UUID.randomUUID();
        OffsetDateTime firstOccurrence = ZonedDateTime
                .of(2030, 4, 9, 11, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime seriesEnd = firstOccurrence.plusHours(1);
        Instant oneOffStart = firstOccurrence.toInstant();
        Instant oneOffEnd = firstOccurrence.plusMinutes(30).toInstant();

        List<Outcome> outcomes = runConcurrently(
                () -> service.create(new BookingInput(resource.getId(), oneOffTitle, oneOffStart, oneOffEnd),
                        "qa-one-off-" + UUID.randomUUID()),
                () -> createSeries(resource.getId(), seriesTitle, firstOccurrence, seriesEnd, 3,
                        "qa-recurring-" + UUID.randomUUID()));

        Outcome oneOff = outcomes.get(0);
        Outcome series = outcomes.get(1);

        // SCN-016#A1: the one-off and the recurring request cannot both succeed.
        assertFalse(oneOff.succeeded() && series.succeeded(),
                "the one-off and the recurring request must not both succeed for the same room and "
                        + "time; outcomes: " + describe(outcomes));

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertNoOverlappingRows(rows);

        if (series.succeeded()) {
            ReservationSeriesView view = (ReservationSeriesView) series.value();
            assertNotNull(view.seriesId(),
                    "the successful recurring request must carry the series identifier persisted for its rows");
            assertEquals(view.occurrences(), rows.size(),
                    "the store must hold exactly the successful recurring request's occurrences");
            assertTrue(rows.stream().allMatch(row -> seriesTitle.equals(row.title())),
                    "no row from the competing one-off request may remain; outcomes: " + describe(outcomes));
            Set<UUID> seriesIds = rows.stream()
                    .map(PersistedReservation::seriesId)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(view.seriesId()), seriesIds,
                    "the persisted rows must carry exactly the recurring request's series identifier");
        } else if (oneOff.succeeded()) {
            assertEquals(1, rows.size(),
                    "the store must hold exactly the successful one-off reservation");
            assertEquals(oneOffTitle, rows.get(0).title(),
                    "no row from the competing recurring request may remain; outcomes: " + describe(outcomes));
            assertNull(rows.get(0).seriesId(),
                    "a one-off reservation must not be recorded as part of a series");
        } else {
            assertTrue(rows.isEmpty(),
                    "when neither request succeeds no reservation row may be persisted");
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource(boolean approvalRequired) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB002 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, approvalRequired);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                              OffsetDateTime endsAt, int occurrences, String actor) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        return service.createSeries(input, actor);
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedReservation> persistedReservations(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title, r.status, r.bookedBy "
                                    + "from Reservation r where r.resource.id = :resourceId "
                                    + "order by r.startsAt, r.id", Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedReservation> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedReservation(
                        ((Number) row[0]).longValue(),
                        toInstant(row[1]),
                        toInstant(row[2]),
                        (UUID) row[3],
                        (String) row[4],
                        (ReservationStatus) row[5],
                        (String) row[6]));
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

    /** Expected weekly occurrence instants, derived in the series' own local time zone. */
    private static Set<Instant> weeklyInstants(OffsetDateTime firstStart, int occurrences) {
        ZonedDateTime firstLocal = firstStart.atZoneSameInstant(BERLIN);
        return java.util.stream.IntStream.range(0, occurrences)
                .mapToObj(index -> firstLocal.plusWeeks(index).toInstant())
                .collect(Collectors.toSet());
    }

    private static void assertNoOverlappingRows(List<PersistedReservation> rows) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                boolean overlaps = rows.get(i).startsAt().isBefore(rows.get(j).endsAt())
                        && rows.get(j).startsAt().isBefore(rows.get(i).endsAt());
                assertFalse(overlaps,
                        "the committed store must not contain two overlapping bookings for the same "
                                + "room and time, but rows " + rows.get(i) + " and " + rows.get(j)
                                + " overlap");
            }
        }
    }

    /**
     * Releases both actions through one barrier so the two service transactions really compete,
     * then waits for both with bounded timeouts.
     */
    private static List<Outcome> runConcurrently(Callable<Object> first, Callable<Object> second) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "wb002-race-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        });
        try {
            Callable<Outcome> firstTask = () -> awaitBarrierThenRun(barrier, first);
            Callable<Outcome> secondTask = () -> awaitBarrierThenRun(barrier, second);
            List<Future<Outcome>> futures = List.of(
                    executor.submit(firstTask),
                    executor.submit(secondTask));
            List<Outcome> outcomes = new ArrayList<>(2);
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    fail("a concurrent request did not complete within 60 seconds");
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

    private static Outcome awaitBarrierThenRun(CyclicBarrier barrier, Callable<Object> action) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception barrierFailure) {
            return new Outcome(null, new IllegalStateException(
                    "both concurrent requests could not be released together", barrierFailure));
        }
        try {
            return new Outcome(action.call(), null);
        } catch (RuntimeException runtimeFailure) {
            return new Outcome(null, runtimeFailure);
        } catch (Exception checkedFailure) {
            return new Outcome(null, new IllegalStateException(checkedFailure));
        }
    }

    private static String describe(List<Outcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.succeeded()
                        ? "SUCCESS " + outcome.value()
                        : "FAILURE " + outcome.failure().getClass().getName()
                                + ": " + outcome.failure().getMessage())
                .collect(Collectors.joining(" | "));
    }

    private record Outcome(Object value, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    private record PersistedReservation(Long id, Instant startsAt, Instant endsAt, UUID seriesId,
                                        String title, ReservationStatus status, String bookedBy) {
    }
}
