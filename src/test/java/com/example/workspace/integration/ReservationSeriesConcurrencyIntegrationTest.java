package com.example.workspace.integration;

import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer obligations SCN-015#A1, SCN-015#A2 and SCN-015#A4 (job WBATCH-006).
 *
 * <p>Boundary: the full Spring Boot application context is loaded, so both racing requests run
 * through the application's real transaction proxy, connection pool and persistence configuration
 * rather than through a sliced test context. Both requests are released through one shared start
 * gate on separate threads, and durable state is read back through JPQL afterwards.</p>
 *
 * <p>A race outcome only counts as a loser when it failed with conflict semantics; a lock timeout
 * or any other infrastructure error is not accepted as an ordinary loser. The losing response's
 * HTTP body (SCN-015#A3) is not claimed here.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesConcurrencyIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-015#A1 - two concurrently submitted overlapping series requests cannot both succeed: the
     * race produces one successful creation and one conflict rejection, and the room holds exactly
     * one created series.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-015")
    @Tag("fault_model:concurrency")
    void twoConcurrentOverlappingSeriesCannotBothSucceed() throws Exception {
        BookableResource room = createRoom("SCN015-A1");

        List<Raced> outcomes = race(List.of(
                seriesRequest("ALPHA", "Race Alpha", room.getId(), "alice"),
                seriesRequest("BETA", "Race Beta", room.getId(), "bob")));

        List<Raced> winners = successful(outcomes);
        List<Raced> losers = failed(outcomes);

        assertEquals(1, winners.size(),
                "exactly one of two concurrently submitted overlapping series requests may succeed, outcomes: "
                        + describe(outcomes));
        assertEquals(1, losers.size(),
                "the race must produce exactly one losing request, outcomes: " + describe(outcomes));
        assertConflictRejection(losers.get(0).failure());

        Set<UUID> createdSeriesIds = rowsFor(room.getId()).stream()
                .map(Reservation::getSeriesId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        assertEquals(1, createdSeriesIds.size(),
                "at most one series may be created in the race, found seriesIds " + createdSeriesIds);
        assertEquals(SERIES_OCCURRENCES, rowsFor(room.getId()).size(),
                "the race must persist exactly the occurrences of the one created series, found "
                        + rowsFor(room.getId()).size());
    }

    /**
     * SCN-015#A2 - the request that loses the race persists zero reservation rows from its own
     * series; the winner is read back as a control so the loser's zero row count cannot be an
     * artefact of both requests failing.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-015")
    @Tag("fault_model:concurrency")
    void losingConcurrentSeriesRequestPersistsZeroReservationRows() throws Exception {
        BookableResource room = createRoom("SCN015-A2");

        List<Raced> outcomes = race(List.of(
                seriesRequest("ALPHA", "Race Alpha", room.getId(), "alice"),
                seriesRequest("BETA", "Race Beta", room.getId(), "bob")));

        List<Raced> winners = successful(outcomes);
        List<Raced> losers = failed(outcomes);

        assertEquals(1, winners.size(),
                "exactly one request must win the race for the loser-persistence assertion to be observable, outcomes: "
                        + describe(outcomes));
        assertEquals(1, losers.size(),
                "exactly one request must lose the race, outcomes: " + describe(outcomes));
        assertConflictRejection(losers.get(0).failure());

        Raced loser = losers.get(0);
        Raced winner = winners.get(0);

        assertEquals(0L, rowCount(room.getId(), loser.title()),
                "the losing request must persist zero reservation rows from its own series");
        assertEquals(0, rowsForTitle(room.getId(), loser.title()).size(),
                "no row of the losing series may survive in the database");
        assertEquals(SERIES_OCCURRENCES, rowsForTitle(room.getId(), winner.title()).size(),
                "control: the winning series must be fully persisted");
        assertEquals(SERIES_OCCURRENCES, rowsFor(room.getId()).size(),
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
     * SCN-015#A4 - any series created in the race is persisted in full: all of its occurrences are
     * stored and share one seriesId, and the exact requested occurrence instants are present.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-015")
    @Tag("fault_model:concurrency")
    void anySeriesCreatedInTheRaceIsPersistedInFullUnderOneSeriesId() throws Exception {
        BookableResource room = createRoom("SCN015-A4");

        List<Raced> outcomes = race(List.of(
                seriesRequest("ALPHA", "Race Alpha", room.getId(), "alice"),
                seriesRequest("BETA", "Race Beta", room.getId(), "bob")));

        List<Raced> winners = successful(outcomes);
        assertFalse(winners.isEmpty(),
                "the race must create at least one series for this persistence assertion to be observable, outcomes: "
                        + describe(outcomes));

        List<Reservation> persistedRows = rowsFor(room.getId());
        assertFalse(persistedRows.isEmpty(),
                "a successful series creation must leave persisted reservation rows");
        assertTrue(persistedRows.stream().allMatch(row -> row.getSeriesId() != null),
                "every persisted row of a created series must carry a non-null seriesId, found " + persistedRows);

        Map<UUID, Long> rowsPerSeriesId = persistedRows.stream()
                .collect(Collectors.groupingBy(Reservation::getSeriesId, Collectors.counting()));
        assertEquals(winners.size(), rowsPerSeriesId.size(),
                "every series created in the race must occupy exactly one seriesId grouping, found " + rowsPerSeriesId);
        for (Map.Entry<UUID, Long> entry : rowsPerSeriesId.entrySet()) {
            assertEquals((long) SERIES_OCCURRENCES, entry.getValue().longValue(),
                    "every created series must own all of its occurrences under one seriesId, found " + rowsPerSeriesId);
        }

        Set<Instant> expectedStarts = Set.of(
                SERIES_START.toInstant(),
                SERIES_START.plusWeeks(1).toInstant(),
                SERIES_START.plusWeeks(2).toInstant());
        for (UUID seriesId : rowsPerSeriesId.keySet()) {
            List<Reservation> seriesRows = persistedRows.stream()
                    .filter(row -> seriesId.equals(row.getSeriesId()))
                    .collect(Collectors.toList());
            Set<Instant> starts = seriesRows.stream()
                    .map(Reservation::getStartsAt)
                    .collect(Collectors.toSet());
            assertEquals(expectedStarts, starts,
                    "the created series " + seriesId + " must persist exactly the requested occurrence instants, found "
                            + starts);
        }

        for (Raced winner : winners) {
            assertEquals(SERIES_OCCURRENCES, rowsForTitle(room.getId(), winner.title()).size(),
                    "the created series must be persisted in full with all of its occurrences");
        }
    }

    private RacedRequest seriesRequest(String label, String title, Long resourceId, String actor) {
        return new RacedRequest(label, title, () -> service.createSeries(
                weeklySeries(resourceId, title, SERIES_OCCURRENCES), actor));
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
     * Releases both requests through one shared gate and collects their outcome. A futures timeout
     * is propagated as a test failure instead of being swallowed.
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

    private List<Reservation> rowsForTitle(long resourceId, String title) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId and r.title = :title "
                                + "order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getResultList();
    }

    private String describe(List<Raced> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.label() + "="
                        + (outcome.failure() == null
                        ? "SUCCEEDED"
                        : "FAILED(" + outcome.failure().getClass().getSimpleName() + ")"))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private record RacedRequest(String label, String title, Callable<Object> action) {
    }

    private record Raced(String label, String title, Object result, RuntimeException failure) {
    }
}
