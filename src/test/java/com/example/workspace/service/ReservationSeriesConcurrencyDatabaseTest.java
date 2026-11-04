package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer concurrency obligations for SCN-015 and SCN-016.
 *
 * <p>Both requests of every race are released through one shared start gate and executed on
 * separate threads, so two real service transactions compete against the same room. Each worker
 * thread runs the production {@link ReservationService} in the transaction the service itself
 * opens; the test method is not transactional. Durable state is then read back through JPQL.</p>
 *
 * <p>A race outcome only counts as a loser when it failed with conflict semantics. A lock timeout,
 * an unexpected 500-style failure or any infrastructure error is not accepted as an ordinary loser,
 * because that would let a broken double-booking guard pass unnoticed.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesConcurrencyDatabaseTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesConcurrencyDatabaseTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);
    private static final ZonedDateTime FIRST_START = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
    private static final int OCCURRENCES = 3;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return FIXED_CLOCK;
        }
    }

    @Autowired
    private ReservationService service;

    @Autowired
    private ResourceRepository resourceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from Reservation").executeUpdate();
            entityManager.createQuery("delete from BookableResource").executeUpdate();
        });
    }

    /**
     * SCN-015#A2 - the request that loses the concurrent race persists zero reservation rows from
     * its own series. The winner is read back to prove the race really committed one complete
     * series, so the loser's zero row count is not an artefact of both requests failing.
     */
    @Test
    void losingConcurrentSeriesRequestPersistsZeroReservationRows() throws Exception {
        BookableResource room = createRoom("SCN015-A2");

        List<Raced> outcomes = race(List.of(
                seriesRequest("ALPHA", "Race Alpha", room.getId(), "alice"),
                seriesRequest("BETA", "Race Beta", room.getId(), "bob")));

        List<Raced> winners = successful(outcomes);
        List<Raced> losers = failed(outcomes);

        assertEquals(1, winners.size(),
                "exactly one of two concurrently submitted overlapping series requests must succeed, outcomes: " + outcomes);
        assertEquals(1, losers.size(),
                "the race must produce exactly one losing request for the loser-persistence assertion to be observable, outcomes: " + outcomes);
        assertConflictRejection(losers.get(0).failure());

        Raced loser = losers.get(0);
        Raced winner = winners.get(0);

        assertEquals(0L, rowCount(room.getId(), loser.title()),
                "the losing request must persist zero reservation rows from its own series");
        assertEquals(0, rowsForTitle(room.getId(), loser.title()).size(),
                "no row of the losing series may survive in the database");
        assertEquals(OCCURRENCES, rowsForTitle(room.getId(), winner.title()).size(),
                "control: the winning series must be fully persisted");
        assertEquals(OCCURRENCES, rowsFor(room.getId()).size(),
                "the room must hold exactly the winning series rows and no orphan rows from the losing series, found "
                        + rowsFor(room.getId()).size());
        assertEquals(1, rowsFor(room.getId()).stream()
                        .map(Reservation::getSeriesId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count(),
                "the durable rows of the room must belong to exactly one created series");
    }

    /**
     * SCN-015#A4 - any series created in the race is persisted in full with all of its occurrences
     * sharing one seriesId.
     */
    @Test
    void everySeriesCreatedInTheRaceIsPersistedInFullUnderOneSeriesId() throws Exception {
        BookableResource room = createRoom("SCN015-A4");

        List<Raced> outcomes = race(List.of(
                seriesRequest("ALPHA", "Race Alpha", room.getId(), "alice"),
                seriesRequest("BETA", "Race Beta", room.getId(), "bob")));

        List<Raced> winners = successful(outcomes);
        assertFalse(winners.isEmpty(),
                "the race must create at least one series for this persistence assertion to be observable, outcomes: " + outcomes);

        List<Reservation> persistedRows = rowsFor(room.getId());
        assertTrue(persistedRows.stream().allMatch(row -> row.getSeriesId() != null),
                "every row of a created series must persist a non-null seriesId, found " + persistedRows);

        long expectedRows = 0L;
        for (Raced winner : winners) {
            ReservationSeriesView created = (ReservationSeriesView) winner.result();
            assertNotNull(created.seriesId(), "a created series must report its seriesId");
            assertEquals(OCCURRENCES, created.reservations().size(),
                    "the created series must report every requested occurrence");
            assertTrue(created.reservations().stream()
                            .allMatch(view -> created.seriesId().equals(view.seriesId())),
                    "every reported occurrence of the created series must share its seriesId, found "
                            + created.reservations().stream().map(view -> view.seriesId()).collect(Collectors.toList()));

            List<Reservation> persisted = rowsForTitle(room.getId(), winner.title());
            assertEquals(OCCURRENCES, persisted.size(),
                    "the created series must be persisted in full with all of its occurrences, found " + persisted);
            for (Reservation row : persisted) {
                assertEquals(created.seriesId(), row.getSeriesId(),
                        "every persisted occurrence of the created series must store its seriesId");
            }
            Set<Instant> persistedStarts = persisted.stream()
                    .map(Reservation::getStartsAt)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(FIRST_START.toInstant(),
                            FIRST_START.plusWeeks(1).toInstant(),
                            FIRST_START.plusWeeks(2).toInstant()),
                    persistedStarts,
                    "the created series must persist exactly the requested occurrence instants");
            expectedRows += OCCURRENCES;
        }

        assertEquals(expectedRows, persistedRows.size(),
                "the room must hold exactly the rows of the series created in the race");

        Map<UUID, Long> rowsPerSeriesId = persistedRows.stream()
                .collect(Collectors.groupingBy(Reservation::getSeriesId, Collectors.counting()));
        assertEquals(winners.size(), rowsPerSeriesId.size(),
                "every series created in the race must occupy exactly one seriesId grouping, found " + rowsPerSeriesId);
        for (Map.Entry<UUID, Long> entry : rowsPerSeriesId.entrySet()) {
            assertEquals((long) OCCURRENCES, entry.getValue().longValue(),
                    "every created series must own all of its occurrences under one seriesId, found " + rowsPerSeriesId);
        }
    }

    /**
     * SCN-016#A2 - after the race between a one-off booking and a series occurrence on the same
     * room interval, the room has at most one active reservation for the contested interval,
     * whether it comes from the one-off or from the series.
     */
    @Test
    void roomKeepsAtMostOneActiveReservationForTheContestedInterval() throws Exception {
        BookableResource room = createRoom("SCN016-A2");
        Instant contestedStart = FIRST_START.toInstant();
        Instant contestedEnd = contestedStart.plus(OCCURRENCE_DURATION);

        List<Raced> outcomes = race(List.of(
                new RacedRequest("ONE_OFF", "Race One-off",
                        () -> service.create(new BookingInput(room.getId(), "Race One-off",
                                contestedStart, contestedEnd), "carol")),
                seriesRequest("SERIES", "Race Series", room.getId(), "alice")));

        List<Raced> winners = successful(outcomes);
        List<Raced> losers = failed(outcomes);

        assertEquals(1, winners.size(),
                "exactly one of the concurrently submitted one-off and series requests must succeed, outcomes: " + outcomes);
        assertEquals(1, losers.size(),
                "the race must produce exactly one losing request, outcomes: " + outcomes);
        assertConflictRejection(losers.get(0).failure());

        List<Reservation> activeCovering = rowsFor(room.getId()).stream()
                .filter(row -> row.getStatus() != ReservationStatus.CANCELLED)
                .filter(row -> row.getStartsAt().isBefore(contestedEnd)
                        && contestedStart.isBefore(row.getEndsAt()))
                .collect(Collectors.toList());
        assertEquals(1, activeCovering.size(),
                "the room must have at most one active reservation for the contested interval after the race, found "
                        + activeCovering);

        Reservation survivor = activeCovering.get(0);
        assertEquals(contestedStart, survivor.getStartsAt(),
                "the surviving reservation must occupy the contested start instant");
        assertEquals(contestedEnd, survivor.getEndsAt(),
                "the surviving reservation must occupy the contested end instant");

        Raced winner = winners.get(0);
        if ("SERIES".equals(winner.label())) {
            ReservationSeriesView created = (ReservationSeriesView) winner.result();
            assertEquals("Race Series", survivor.getTitle(),
                    "when the series wins the race, the surviving reservation must be its occurrence");
            assertNotNull(survivor.getSeriesId(),
                    "the surviving series occurrence must carry a seriesId");
            assertEquals(created.seriesId(), survivor.getSeriesId(),
                    "the surviving reservation must belong to the series created by the winning request");
        } else {
            assertEquals("Race One-off", survivor.getTitle(),
                    "when the one-off wins the race, the surviving reservation must be the one-off booking");
            assertNull(survivor.getSeriesId(),
                    "the surviving one-off reservation must not carry a seriesId");
        }
    }

    private RacedRequest seriesRequest(String label, String title, Long resourceId, String actor) {
        return new RacedRequest(label, title, () -> service.createSeries(
                weeklySeries(resourceId, title, OCCURRENCES), actor));
    }

    private List<Raced> successful(List<Raced> outcomes) {
        return outcomes.stream()
                .filter(outcome -> outcome.failure() == null)
                .collect(Collectors.toList());
    }

    private List<Raced> failed(List<Raced> outcomes) {
        return outcomes.stream()
                .filter(outcome -> outcome.failure() != null)
                .collect(Collectors.toList());
    }

    /**
     * Releases all requests through one shared gate and collects their outcome. A futures timeout is
     * propagated as a test failure instead of being swallowed.
     */
    private List<Raced> race(List<RacedRequest> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Raced>> futures = new ArrayList<>();
            for (RacedRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the concurrent start gate was not released");
                    }
                    try {
                        return new Raced(request.label(), request.title(), request.action().call(), null);
                    } catch (RuntimeException failure) {
                        return new Raced(request.label(), request.title(), null, failure);
                    }
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS),
                    "all concurrent requests must reach the start gate before the race begins");
            startGate.countDown();
            List<Raced> outcomes = new ArrayList<>();
            for (Future<Raced> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertConflictRejection(RuntimeException failure) {
        boolean apiConflict =
                failure instanceof ApiException && ((ApiException) failure).status() == HttpStatus.CONFLICT;
        boolean conflictTyped =
                failure.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("conflict");
        assertTrue(apiConflict || conflictTyped,
                "the losing request must fail with conflict semantics, not an infrastructure error; was "
                        + failure.getClass().getName() + ": " + failure.getMessage());
    }

    private BookableResource createRoom(String code) {
        return resourceRepository.save(new BookableResource(code, "Room " + code, ResourceType.ROOM,
                "HQ", 1, 8, true, false));
    }

    private ReservationSeriesInput weeklySeries(Long resourceId, String title, int occurrences) {
        ZonedDateTime firstEnd = FIRST_START.plus(OCCURRENCE_DURATION);
        return new ReservationSeriesInput(resourceId, title,
                FIRST_START.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
    }

    private List<Reservation> rowsFor(long resourceId) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }

    private List<Reservation> rowsForTitle(long resourceId, String title) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId and r.title = :title "
                                + "order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getResultList();
    }

    private long rowCount(long resourceId, String title) {
        return entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.title = :title",
                        Long.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getSingleResult();
    }

    private record RacedRequest(String label, String title, Callable<Object> action) {
    }

    private record Raced(String label, String title, Object result, RuntimeException failure) {
    }
}
