package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer obligations SCN-016#A1 and SCN-016#A2 (job WBATCH-007): a one-off booking and
 * the first occurrence of a weekly series, submitted concurrently for the same room interval,
 * cannot both succeed and leave at most one active reservation covering that interval.
 *
 * <p>Boundary: the full Spring Boot application context is loaded (no slice, no mocks) and both
 * competing requests are driven through the production {@link com.example.workspace.service.ReservationService}
 * Spring proxy, i.e. through the application's real transaction advice, connection pool and lock
 * discipline. The two requests are released by one shared start gate on separate threads.
 * Committed state is read back through JPQL in a separate persistence context, so the side effect
 * is observed on the durable rows rather than on the service return values. The HTTP status mapping
 * of the losing request is the API layer's obligation and is not claimed here.</p>
 *
 * <p>Each request shape is first applied on its own control room, so a race where both requests are
 * rejected for an unrelated reason cannot pass: the only difference between the controls and the
 * contested room is the concurrent contest itself.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationOneOffSeriesRaceIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-016#A1 - a concurrently submitted one-off booking and a series occurrence targeting the
     * same room and overlapping interval cannot both succeed.
     *
     * <p>The race must produce exactly one committed winner and exactly one losing request whose
     * failure carries conflict semantics; a lock timeout, a 5xx-style failure or any other
     * infrastructure error is not accepted as the ordinary loser.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-016")
    @Tag("fault_model:concurrency")
    void concurrentOneOffAndSeriesOccurrenceCannotBothSucceed() throws Exception {
        BookableResource contestedRoom = createRoom("SCN016-A1");
        BookableResource oneOffControlRoom = createRoom("SCN016-A1-CTRL-ONE");
        BookableResource seriesControlRoom = createRoom("SCN016-A1-CTRL-SERIES");
        Instant contestedStart = SERIES_START.toInstant();
        Instant contestedEnd = contestedStart.plus(OCCURRENCE_DURATION);

        service.create(new BookingInput(oneOffControlRoom.getId(), "Control one-off",
                contestedStart, contestedEnd), "carol");
        assertEquals(1, rowsFor(oneOffControlRoom.getId()).size(),
                "control fixture: the one-off request must be accepted on an unbooked room");

        service.createSeries(weeklySeries(seriesControlRoom.getId(), "Control series", SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, rowsFor(seriesControlRoom.getId()).size(),
                "control fixture: the series request must be accepted on an unbooked room");

        List<Raced> outcomes = race(List.of(
                oneOffRequest("ONE_OFF", "Race One-off", contestedRoom.getId(), contestedStart, contestedEnd),
                seriesRequest("SERIES", "Race Series", contestedRoom.getId())));

        assertEquals(1, successful(outcomes).size(),
                "exactly one of the concurrently submitted one-off and series requests may succeed, outcomes: "
                        + describe(outcomes));
        assertEquals(1, failed(outcomes).size(),
                "the race must produce exactly one losing request, outcomes: " + describe(outcomes));
        assertConflictRejection(failed(outcomes).get(0).failure());
    }

    /**
     * SCN-016#A2 - after the race the room has at most one active reservation for the contested
     * interval, whether the survivor comes from the one-off request or from the series.
     *
     * <p>The survivor is read from the committed rows (a {@code PENDING} hold is still active; only
     * {@code CANCELLED} rows are excluded) and is matched against the request that actually won the
     * race, so a row from the losing request can never be mistaken for the winner's row.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-016")
    @Tag("fault_model:concurrency")
    void roomKeepsSingleActiveReservationForContestedIntervalAfterRace() throws Exception {
        BookableResource room = createRoom("SCN016-A2");
        Instant contestedStart = SERIES_START.toInstant();
        Instant contestedEnd = contestedStart.plus(OCCURRENCE_DURATION);

        List<Raced> outcomes = race(List.of(
                oneOffRequest("ONE_OFF", "Race One-off", room.getId(), contestedStart, contestedEnd),
                seriesRequest("SERIES", "Race Series", room.getId())));

        List<Raced> winners = successful(outcomes);
        List<Raced> losers = failed(outcomes);
        assertEquals(1, winners.size(),
                "exactly one of the concurrently submitted one-off and series requests must succeed for the "
                        + "durable side effect to be observable, outcomes: " + describe(outcomes));
        assertEquals(1, losers.size(),
                "the race must produce exactly one losing request, outcomes: " + describe(outcomes));
        assertConflictRejection(losers.get(0).failure());

        List<Reservation> activeCovering = rowsFor(room.getId()).stream()
                .filter(row -> row.getStatus() != ReservationStatus.CANCELLED)
                .filter(row -> row.getStartsAt().isBefore(contestedEnd) && contestedStart.isBefore(row.getEndsAt()))
                .collect(Collectors.toList());
        assertEquals(1, activeCovering.size(),
                "the room must hold at most one active reservation for the contested interval after the race, found "
                        + activeCovering);

        Reservation survivor = activeCovering.get(0);
        assertEquals(contestedStart, survivor.getStartsAt(),
                "the surviving reservation must occupy the contested start instant");
        assertEquals(contestedEnd, survivor.getEndsAt(),
                "the surviving reservation must occupy the contested end instant");

        Raced winner = winners.get(0);
        if ("SERIES".equals(winner.label())) {
            assertEquals("Race Series", survivor.getTitle(),
                    "when the series wins the race, the surviving reservation must be its occurrence");
        } else {
            assertEquals("Race One-off", survivor.getTitle(),
                    "when the one-off wins the race, the surviving reservation must be the one-off booking");
        }
    }

    private RacedRequest oneOffRequest(String label, String title, Long resourceId,
                                       Instant start, Instant end) {
        return new RacedRequest(label, title, () -> service.create(
                new BookingInput(resourceId, title, start, end), "carol"));
    }

    private RacedRequest seriesRequest(String label, String title, Long resourceId) {
        return new RacedRequest(label, title, () -> service.createSeries(
                weeklySeries(resourceId, title, SERIES_OCCURRENCES), "alice"));
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
