package com.example.workspace.integration;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationController;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Integration-layer proof for SCN-015#A1 and SCN-015#A2 (REQ-009, SE-003, SE-001, SE-004).
 *
 * A recurring 3-occurrence series and an overlapping one-off reservation are submitted genuinely
 * concurrently through the real {@link ReservationController} bean of the running application context,
 * each call carrying a real {@link Authentication}. Both requests therefore execute through the real
 * transactional service, the shared resource lock and the configured datasource; nothing is mocked.
 *
 * The frozen outcomes are observed together, from the single race they belong to:
 * A1 - exactly one of the two requests books the room (the non-winner must be the frozen conflict
 * rejection, never a 5xx, timeout, deadlock or unrelated validation failure, and a race where neither
 * books fails this case);
 * A2 - the non-winning request leaves no row for the contested room time, established by pinning the
 * complete committed row set to the winner's exact request shape, read back from the datasource after
 * both calls completed instead of trusting either response body.
 *
 * The race is one atomic observation, so both assertions are discharged by the same native case; two
 * separate races would observe different executions and could disagree. The fixture week is unique and
 * in the future, and every row created by the application plus the fixture resource is removed in
 * {@code @AfterEach}.
 */
@SpringBootTest
class RecurringVersusOneOffRaceIntegrationTest {

    private static final String SERIES_SIDE = "recurring series";
    private static final String ONE_OFF_SIDE = "one-off reservation";
    private static final long CALL_TIMEOUT_MILLIS = 30_000L;

    @Autowired
    private ReservationController reservationController;

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

    @BeforeEach
    void authenticateCaller() {
        SecurityContextHolder.getContext().setAuthentication(alice());
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void concurrentOverlappingRecurringAndOneOffRequestsProduceExactlyOneBooker() throws Exception {
        // Series occurrences: 2031-10-06 / 10-13 / 10-20 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z. The one-off 09:30-10:30 Berlin on 2031-10-06 => 07:30-08:30Z overlaps only
        // the first occurrence, so exactly one request must win the contested 07:00-08:00Z hour.
        useWindow("2031-10-06T06:00:00Z", "2031-10-20T09:00:00Z");
        BookableResource room = persistRoom("WB015I");

        Instant oneOffStart = Instant.parse("2031-10-06T07:30:00Z");
        Instant oneOffEnd = Instant.parse("2031-10-06T08:30:00Z");
        Set<Instant> expectedSeriesStarts = Set.of(
                Instant.parse("2031-10-06T07:00:00Z"),
                Instant.parse("2031-10-13T07:00:00Z"),
                Instant.parse("2031-10-20T07:00:00Z"));

        ReservationSeriesInput seriesInput = seriesInput(room, "WB015 integration series",
                "2031-10-06T09:00:00+02:00", "2031-10-06T10:00:00+02:00", 3);
        BookingInput oneOffInput = new BookingInput(room.getId(), "WB015 integration one-off",
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
                return RaceOutcome.responded(SERIES_SIDE,
                        reservationController.createSeries(seriesInput, alice()));
            } catch (Throwable failure) {
                return RaceOutcome.failed(SERIES_SIDE, failure);
            }
        });
        Future<RaceOutcome> oneOffFuture = pool.submit(() -> {
            bothReady.countDown();
            startGate.await();
            try {
                return RaceOutcome.responded(ONE_OFF_SIDE,
                        reservationController.create(oneOffInput, alice()));
            } catch (Throwable failure) {
                return RaceOutcome.failed(ONE_OFF_SIDE, failure);
            }
        });

        assertTrue(bothReady.await(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                "both concurrent requests must reach the start gate before the race is released");
        startGate.countDown();

        RaceOutcome seriesOutcome = seriesFuture.get(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        RaceOutcome oneOffOutcome = oneOffFuture.get(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        String race = "series " + describe(seriesOutcome) + "; one-off " + describe(oneOffOutcome);
        int winners = (seriesOutcome.succeeded() ? 1 : 0) + (oneOffOutcome.succeeded() ? 1 : 0);
        assertEquals(1, winners,
                "exactly one of the two concurrent overlapping requests must create reservations, but "
                        + "the observed outcome was: " + race);

        RaceOutcome loser = seriesOutcome.succeeded() ? oneOffOutcome : seriesOutcome;
        assertTrue(loser.conflictRejection(),
                "the non-winning request must be rejected as a booking conflict, and an unexpected "
                        + "error, 5xx or timeout is not an acceptable loser, but it failed with: "
                        + describe(loser));

        List<Reservation> stored = storedInWindow();

        if (seriesOutcome.succeeded()) {
            assertEquals(3, stored.size(),
                    "the winning recurring request must leave exactly its three occurrences and the "
                            + "losing one-off must leave no row; stored rows: " + describeRows(stored));
            assertEquals(expectedSeriesStarts, startsOf(stored),
                    "the stored rows must be exactly the three series occurrences; stored rows: "
                            + describeRows(stored));
            for (Reservation row : stored) {
                assertEquals(row.getStartsAt().plus(Duration.ofHours(1)), row.getEndsAt(),
                        "each stored series occurrence must keep its one-hour requested duration; "
                                + "stored rows: " + describeRows(stored));
            }
            List<UUID> seriesIds = stored.stream()
                    .map(Reservation::getSeriesId)
                    .collect(Collectors.toList());
            assertTrue(seriesIds.stream().noneMatch(Objects::isNull),
                    "every stored occurrence of the winning series must carry a non-null series "
                            + "identifier, but got " + seriesIds + "; stored rows: "
                            + describeRows(stored));
            assertEquals(1, new HashSet<>(seriesIds).size(),
                    "every stored occurrence of the winning series must share one series identifier, "
                            + "but got " + seriesIds + "; stored rows: " + describeRows(stored));
            assertTrue(stored.stream().noneMatch(row ->
                            oneOffStart.equals(row.getStartsAt()) && oneOffEnd.equals(row.getEndsAt())),
                    "the non-winning one-off request created a reservation at its contested room time; "
                            + "stored rows: " + describeRows(stored));
        } else {
            assertEquals(1, stored.size(),
                    "the winning one-off request must leave exactly its single reservation and the "
                            + "losing series must leave no occurrence; stored rows: "
                            + describeRows(stored));
            Reservation row = stored.get(0);
            assertEquals(oneOffStart, row.getStartsAt(),
                    "the surviving row must be the winning one-off reservation at its requested start; "
                            + "stored rows: " + describeRows(stored));
            assertEquals(oneOffEnd, row.getEndsAt(),
                    "the surviving row must be the winning one-off reservation at its requested end; "
                            + "stored rows: " + describeRows(stored));
            assertNull(row.getSeriesId(),
                    "the surviving one-off reservation must be stored without a series identifier, but "
                            + "got " + row.getSeriesId() + "; stored rows: " + describeRows(stored));
            assertTrue(stored.stream().noneMatch(row2 -> expectedSeriesStarts.contains(row2.getStartsAt())),
                    "the non-winning recurring request created a reservation for the contested room "
                            + "time; stored rows: " + describeRows(stored));
        }
    }

    private record RaceOutcome(String side, ResponseEntity<?> response, Throwable failure) {

        static RaceOutcome responded(String side, ResponseEntity<?> response) {
            return new RaceOutcome(side, Objects.requireNonNull(response, "response must not be null"), null);
        }

        static RaceOutcome failed(String side, Throwable failure) {
            return new RaceOutcome(side, null, Objects.requireNonNull(failure, "failure must not be null"));
        }

        boolean succeeded() {
            return failure == null && response != null && response.getStatusCode().is2xxSuccessful();
        }

        boolean conflictRejection() {
            if (failure != null) {
                return isConflictCarrier(failure);
            }
            return response != null && HttpStatus.CONFLICT.equals(response.getStatusCode());
        }
    }

    private static boolean isConflictCarrier(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiException apiException
                    && HttpStatus.CONFLICT.equals(apiException.status())) {
                return true;
            }
            if ("ReservationConflictException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static String describe(RaceOutcome outcome) {
        if (outcome.failure() != null) {
            return outcome.side() + " failed with " + outcome.failure();
        }
        return outcome.side() + " responded " + outcome.response().getStatusCode();
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
        SecurityContextHolder.clearContext();
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
                code, "Integration fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, false));
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

    private static Set<Instant> startsOf(List<Reservation> rows) {
        return rows.stream().map(Reservation::getStartsAt).collect(Collectors.toSet());
    }

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
