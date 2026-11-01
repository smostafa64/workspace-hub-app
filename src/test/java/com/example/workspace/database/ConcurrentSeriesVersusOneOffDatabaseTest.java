package com.example.workspace.database;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer proof for SCN-015#A2 (REQ-009).
 *
 * A recurring series and an overlapping one-off reservation are submitted genuinely concurrently against
 * the same resource through two threads, so the shared resource lock and the real datasource are
 * exercised. The durable outcome is then read back from the configured database through the repository,
 * independently of either response body.
 *
 * The frozen assertion is that the non-winning request creates no reservation for the contested room
 * time. To attribute rows to a request it is not enough to count rows in the contested interval, because
 * both requests overlap it: the series would leave three rows at its three occurrence starts (one of them
 * inside the contested hour) while the one-off would leave exactly one row at its own interval. Both
 * branches below therefore pin the complete committed row set to the winner's exact request shape.
 *
 * The race must produce exactly one booker. A run where neither request books proves nothing (it may be a
 * validation or transaction failure, not mutual exclusion) and, like any unexpected non-conflict
 * rejection (5xx, lock timeout, deadlock), is reported as a failure rather than counted as an ordinary
 * loser.
 */
@SpringBootTest
class ConcurrentSeriesVersusOneOffDatabaseTest {

    private static final String SERIES_SIDE = "recurring series";
    private static final String ONE_OFF_SIDE = "one-off reservation";
    private static final long CALL_TIMEOUT_MILLIS = 30_000L;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long fixtureResourceId;
    private Instant windowStart;
    private Instant windowEnd;
    private final List<Long> fixtureReservationIds = new ArrayList<>();
    private ExecutorService pool;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void overlappingSeriesAndOneOffRaceCreatesExactlyOneBookerAndOnlyItsRows() throws Exception {
        // Series occurrences: 2031-07-07 / 07-14 / 07-21 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z. One-off: 09:30-10:30 Berlin on 2031-07-07 => 07:30-08:30Z, which overlaps
        // only the first occurrence inside the contested 07:00-08:00Z hour.
        useWindow("2031-07-07T06:00:00Z", "2031-07-21T09:00:00Z");
        BookableResource room = persistRoom("WB015A");

        Instant oneOffStart = Instant.parse("2031-07-07T07:30:00Z");
        Instant oneOffEnd = Instant.parse("2031-07-07T08:30:00Z");
        Set<Instant> expectedSeriesStarts = Set.of(
                Instant.parse("2031-07-07T07:00:00Z"),
                Instant.parse("2031-07-14T07:00:00Z"),
                Instant.parse("2031-07-21T07:00:00Z"));

        ReservationSeriesInput seriesInput = seriesInput(room, "WB015 recurring series",
                "2031-07-07T09:00:00+02:00", "2031-07-07T10:00:00+02:00", 3);
        BookingInput oneOffInput = new BookingInput(room.getId(), "WB015 one-off",
                oneOffStart, oneOffEnd);

        pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<RaceOutcome> seriesFuture = pool.submit(() -> {
            bothReady.countDown();
            startGate.await();
            try {
                reservationService.createSeries(seriesInput, "alice");
                return RaceOutcome.success(SERIES_SIDE);
            } catch (Throwable failure) {
                return RaceOutcome.failure(SERIES_SIDE, failure);
            }
        });
        Future<RaceOutcome> oneOffFuture = pool.submit(() -> {
            bothReady.countDown();
            startGate.await();
            try {
                reservationService.create(oneOffInput, "alice");
                return RaceOutcome.success(ONE_OFF_SIDE);
            } catch (Throwable failure) {
                return RaceOutcome.failure(ONE_OFF_SIDE, failure);
            }
        });

        assertTrue(bothReady.await(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                "both concurrent requests must reach the start gate before the race is released");
        startGate.countDown();

        RaceOutcome seriesOutcome = seriesFuture.get(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        RaceOutcome oneOffOutcome = oneOffFuture.get(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        String race = "series " + describe(seriesOutcome) + "; one-off " + describe(oneOffOutcome);
        assertTrue(seriesOutcome.succeeded() ^ oneOffOutcome.succeeded(),
                "exactly one of the two concurrent overlapping requests must create reservations, "
                        + "but the observed outcome was: " + race);

        RaceOutcome loser = seriesOutcome.succeeded() ? oneOffOutcome : seriesOutcome;
        assertTrue(isConflictRejection(loser.failure()),
                "the non-winning request must be rejected as a booking conflict and not fail with an "
                        + "unexpected error, but it failed with: " + describe(loser));

        List<Reservation> stored = storedInWindow();

        if (seriesOutcome.succeeded()) {
            assertEquals(3, stored.size(),
                    "the winning recurring request must leave exactly its three occurrences and the "
                            + "losing one-off must leave no row; stored rows: " + describeRows(stored));
            assertEquals(expectedSeriesStarts, startsOf(stored),
                    "the stored rows must be exactly the three series occurrences; stored rows: "
                            + describeRows(stored));
            assertTrue(stored.stream().noneMatch(row ->
                            oneOffStart.equals(row.getStartsAt()) && oneOffEnd.equals(row.getEndsAt())),
                    "the non-winning one-off request created a reservation at its contested room time; "
                            + "stored rows: " + describeRows(stored));
        } else {
            assertEquals(1, stored.size(),
                    "the winning one-off request must leave exactly its single reservation and the "
                            + "losing series must leave no row; stored rows: " + describeRows(stored));
            Reservation row = stored.get(0);
            assertEquals(oneOffStart, row.getStartsAt(),
                    "the surviving row must be the one-off reservation at its requested start; stored rows: "
                            + describeRows(stored));
            assertEquals(oneOffEnd, row.getEndsAt(),
                    "the surviving row must be the one-off reservation at its requested end; stored rows: "
                            + describeRows(stored));
            assertNull(row.getSeriesId(),
                    "the surviving one-off reservation must be stored without a series identifier");
            assertTrue(stored.stream().noneMatch(row2 -> expectedSeriesStarts.contains(row2.getStartsAt())),
                    "the non-winning recurring request created a reservation for the contested room time; "
                            + "stored rows: " + describeRows(stored));
        }
    }

    private static boolean isConflictRejection(Throwable failure) {
        if (failure instanceof ApiException apiException) {
            return HttpStatus.CONFLICT.equals(apiException.status());
        }
        // The conflict carrier exposed by the technical signatures; it is the series-side 409 signal.
        return failure != null
                && "ReservationConflictException".equals(failure.getClass().getSimpleName());
    }

    private static String describe(RaceOutcome outcome) {
        return outcome.succeeded()
                ? outcome.side() + " succeeded"
                : outcome.side() + " failed with " + outcome.failure();
    }

    private static Set<Instant> startsOf(List<Reservation> rows) {
        return rows.stream().map(Reservation::getStartsAt).collect(Collectors.toSet());
    }

    private void useWindow(String from, String to) {
        windowStart = Instant.parse(from);
        windowEnd = Instant.parse(to);
        storedInWindow().forEach(row -> deleteReservation(row.getId()));
    }

    private List<Reservation> storedInWindow() {
        if (windowStart == null || windowEnd == null) {
            return List.of();
        }
        return reservationRepository.findAll().stream()
                .filter(row -> row.getStartsAt() != null && row.getEndsAt() != null)
                .filter(row -> row.getStartsAt().isBefore(windowEnd) && row.getEndsAt().isAfter(windowStart))
                .collect(Collectors.toList());
    }

    @AfterEach
    void removeFixtures() {
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            pool = null;
        }
        storedInWindow().forEach(row -> deleteReservation(row.getId()));
        for (Long id : new ArrayList<>(fixtureReservationIds)) {
            deleteReservation(id);
        }
        if (fixtureResourceId != null) {
            resourceRepository.findById(fixtureResourceId)
                    .ifPresent(resource -> resourceRepository.deleteById(fixtureResourceId));
        }
        fixtureReservationIds.clear();
        fixtureResourceId = null;
        windowStart = null;
        windowEnd = null;
    }

    private void deleteReservation(Long id) {
        if (id == null) {
            return;
        }
        if (reservationRepository.findById(id).isPresent()) {
            reservationRepository.deleteById(id);
        }
    }

    private BookableResource persistRoom(String codePrefix) {
        String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        BookableResource saved = resourceRepository.save(new BookableResource(
                code, "Database fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, false));
        fixtureResourceId = saved.getId();
        assertNotNull(fixtureResourceId, "the resource fixture must be persisted with an id");
        return saved;
    }

    private ReservationSeriesInput seriesInput(BookableResource room, String title, String startsAt,
                                               String endsAt, int occurrences) throws Exception {
        String json = "{\"resourceId\":" + room.getId()
                + ",\"title\":\"" + title + "\""
                + ",\"startsAt\":\"" + startsAt + "\""
                + ",\"endsAt\":\"" + endsAt + "\""
                + ",\"timezone\":\"Europe/Berlin\""
                + ",\"frequency\":\"WEEKLY\""
                + ",\"occurrences\":" + occurrences + "}";
        return objectMapper.readValue(json, ReservationSeriesInput.class);
    }

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus()
                        + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }

    private record RaceOutcome(String side, Throwable failure) {

        static RaceOutcome success(String side) {
            return new RaceOutcome(side, null);
        }

        static RaceOutcome failure(String side, Throwable failure) {
            return new RaceOutcome(side, Objects.requireNonNull(failure, "failure must not be null"));
        }

        boolean succeeded() {
            return failure == null;
        }
    }
}
