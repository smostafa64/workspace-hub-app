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
 * Database-layer verification for SCN-008#A3: "After the concurrent attempts no two reservation
 * rows for the room overlap in time."
 *
 * <p>Each test dispatches a genuinely competing pair of requests through the real
 * {@link ReservationService} bean against the embedded H2 database owned by this test class
 * (approved {@code embedded-h2-unique-db} fixture). Both requests are released through one barrier
 * and target the same room and an overlapping interval, so the only difference to the serial
 * control is the concurrency itself. Because the test transaction propagation is
 * {@link Propagation#NOT_SUPPORTED}, each service call runs in its own transaction and really
 * commits. The resulting committed rows are then read through a brand-new JPA
 * {@link EntityManager} and the pairwise non-overlap invariant is evaluated over every row of the
 * raced room.</p>
 *
 * <p>Only SCN-008#A3 is asserted. Which attempt wins, whether either attempt wins, and the status
 * of a losing attempt are not asserted here (those belong to SCN-008#A1/A2 and the unspecified
 * loser contract, AMB-008). A separate, freshly owned control room proves that the same payload is
 * accepted serially, so a failure of the raced attempts cannot silently make the invariant vacuous
 * for a payload-level reason. Each method owns UUID-coded resources and the database name is
 * generated per class, so no invocation can observe another invocation's rows.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class,
        ReservationSeriesConcurrencyPersistenceTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesConcurrencyPersistenceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int WEEKLY_OCCURRENCES = 3;
    private static final long BARRIER_TIMEOUT_SECONDS = 15;
    private static final long FUTURE_TIMEOUT_SECONDS = 90;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb002conc_" + UUID.randomUUID().toString().replace("-", "");

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
    // SCN-008#A3 - two overlapping series requests dispatched concurrently
    // "After the concurrent attempts no two reservation rows for the room
    // overlap in time."
    // ---------------------------------------------------------------------
    @Test
    @Timeout(150)
    void scn008ConcurrentOverlappingSeriesLeaveNoOverlappingRowsForTheRoom() {
        BookableResource racedRoom = newResource();
        assertTrue(racedRoom.isActive(), "precondition: the owned room must be active");

        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 6, 4, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);
        String titleA = "scn008-series-a-" + UUID.randomUUID();
        String titleB = "scn008-series-b-" + UUID.randomUUID();

        assertEquals(0, readRows(racedRoom.getId()).size(),
                "precondition: the owned room must have no prior reservations in the slot");

        // Serial control on a separate, freshly owned room: the identical series payload is
        // accepted and persisted, so a failure of the raced pair cannot be caused by the payload
        // or by the fixed clock. The control room is distinct from the raced room.
        BookableResource controlRoom = newResource();
        ReservationSeriesView control = createSeries(controlRoom.getId(),
                "scn008-control-" + UUID.randomUUID(), startsAt, endsAt, WEEKLY_OCCURRENCES);
        assertNotNull(control,
                "control: the identical series payload must be accepted on a separate free room");
        assertEquals(WEEKLY_OCCURRENCES, readRows(controlRoom.getId()).size(),
                "control: the separate free room must persist every occurrence of the series");

        List<Outcome> outcomes = runConcurrently(
                () -> createSeries(racedRoom.getId(), titleA, startsAt, endsAt, WEEKLY_OCCURRENCES),
                () -> createSeries(racedRoom.getId(), titleB, startsAt, endsAt, WEEKLY_OCCURRENCES));
        assertEquals(2, outcomes.size(),
                "control: both concurrent attempts must settle before the store is observed");

        List<PersistedRow> rows = readRows(racedRoom.getId());
        assertNoOverlappingRows(racedRoom.getId(), rows, outcomes);
    }

    // ---------------------------------------------------------------------
    // SCN-008#A3 - one recurring series and one one-off dispatched concurrently
    // "After the concurrent attempts no two reservation rows for the room
    // overlap in time."
    //
    // Kept as a distinct native invocation because the competing writer is the
    // different create() path: REQ-007 requires the one-off and the recurring
    // path to use the same resource lock discipline, so a missing lock confined
    // to one write path is independently falsifiable only through this pair.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(150)
    void scn008ConcurrentSeriesAndOneOffLeaveNoOverlappingRowsForTheRoom() {
        BookableResource racedRoom = newResource();
        assertTrue(racedRoom.isActive(), "precondition: the owned room must be active");

        OffsetDateTime seriesStartsAt = ZonedDateTime
                .of(2030, 7, 9, 11, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime seriesEndsAt = seriesStartsAt.plusHours(1);
        OffsetDateTime oneOffStartsAt = seriesStartsAt.plusMinutes(15);
        OffsetDateTime oneOffEndsAt = seriesStartsAt.plusMinutes(45);
        String seriesTitle = "scn008-recurring-" + UUID.randomUUID();
        String oneOffTitle = "scn008-one-off-" + UUID.randomUUID();

        assertEquals(0, readRows(racedRoom.getId()).size(),
                "precondition: the owned room must have no prior reservations in the slot");

        // Serial control on a separate, freshly owned room: the one-off payload is accepted and
        // persisted, so the one-off side of the race is known to be valid on its own.
        BookableResource controlRoom = newResource();
        ReservationView control = createOneOff(controlRoom.getId(),
                "scn008-control-one-off-" + UUID.randomUUID(), oneOffStartsAt, oneOffEndsAt);
        assertNotNull(control,
                "control: the identical one-off payload must be accepted on a separate free room");
        assertEquals(1, readRows(controlRoom.getId()).size(),
                "control: the separate free room must persist the accepted one-off reservation");

        List<Outcome> outcomes = runConcurrently(
                () -> createSeries(racedRoom.getId(), seriesTitle, seriesStartsAt, seriesEndsAt,
                        WEEKLY_OCCURRENCES),
                () -> createOneOff(racedRoom.getId(), oneOffTitle, oneOffStartsAt, oneOffEndsAt));
        assertEquals(2, outcomes.size(),
                "control: both concurrent attempts must settle before the store is observed");

        List<PersistedRow> rows = readRows(racedRoom.getId());
        assertNoOverlappingRows(racedRoom.getId(), rows, outcomes);
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB002 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
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
                "input control: timezone must be retained");
        assertEquals(SERIES_FREQUENCY, input.frequency(),
                "input control: the weekly frequency must be retained");
        assertEquals(occurrences, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return service.createSeries(input, "qa-" + UUID.randomUUID());
    }

    private ReservationView createOneOff(long resourceId, String title, OffsetDateTime startsAt,
                                         OffsetDateTime endsAt) {
        Instant start = startsAt.toInstant();
        Instant end = endsAt.toInstant();
        assertTrue(start.isBefore(end),
                "input control: the one-off interval must be a genuine future interval");
        BookingInput input = new BookingInput(resourceId, title, start, end);
        return service.create(input, "qa-one-off-" + UUID.randomUUID());
    }

    /**
     * Reads every committed row of one owned room through a brand-new EntityManager, after both
     * concurrent service transactions have settled.
     */
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

    /**
     * SCN-008#A3: no two committed reservation rows of the room overlap in time. Half-open
     * intervals are used, so rows that merely touch at an endpoint do not overlap.
     */
    private static void assertNoOverlappingRows(long resourceId, List<PersistedRow> rows,
                                                List<Outcome> outcomes) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                PersistedRow first = rows.get(i);
                PersistedRow second = rows.get(j);
                boolean overlaps = first.startsAt().isBefore(second.endsAt())
                        && second.startsAt().isBefore(first.endsAt());
                assertFalse(overlaps,
                        "after the concurrent attempts no two reservation rows for room "
                                + resourceId + " may overlap in time, but rows " + first
                                + " and " + second + " overlap; all committed rows were " + rows
                                + "; outcomes: " + describe(outcomes));
            }
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
            Thread thread = new Thread(runnable, "wb002-race-" + UUID.randomUUID());
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
            // A failing attempt is recorded, not asserted: the losing attempt's status is
            // unspecified (AMB-008) and SCN-008#A1/A2 are outside this database obligation.
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
