package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationView;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import com.example.workspace.service.ResourceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration-layer verification of the resource-lock discipline for competing writes
 * (SCN-015 and SCN-016, REQ-007).
 *
 * <p>Each test releases two real write paths at the same instant through a barrier and lets the
 * real service + JPA layer contend for the same room. One path is always the documented HTTP
 * entry point (POST /api/reservation-series); the competing path is either a second identical
 * series request or the documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)}. After both callers have finished, the
 * committed rows are read in a fresh transaction.</p>
 *
 * <p>Deliberately asserted and nothing more, because the frozen contract does not specify the
 * losing caller's response: the two overlapping writers cannot both succeed, persisted rows
 * belong to at most one series, and a request that did not succeed left no reservation behind.
 * The losing request's status code is therefore recorded for diagnostics but never asserted.</p>
 *
 * <p>The overlapping slot is 2030-04-14 10:00-10:30 Europe/Berlin (08:00Z-08:30Z), in the future
 * relative to the frozen clock. Fixture rooms are uniquely coded per test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationConcurrentBookingIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    /** Three weekly occurrences starting 2030-04-14 10:00 Europe/Berlin (08:00Z). */
    private static final String SERIES_START = "2030-04-14T10:00:00+02:00";
    private static final String SERIES_END = "2030-04-14T10:30:00+02:00";
    private static final int SERIES_OCCURRENCES = 3;

    /** The one-off slot that overlaps the first series occurrence exactly (08:00Z-08:30Z). */
    private static final Instant ONE_OFF_START = Instant.parse("2030-04-14T08:00:00Z");
    private static final Instant ONE_OFF_END = Instant.parse("2030-04-14T08:30:00Z");

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 60L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-015#A1 (integration) - Two identical overlapping three-occurrence series released at the
     * same instant cannot both succeed. The two statuses are observed from the real HTTP callers,
     * but the losing caller's specific status is not asserted (the contract leaves it open).
     */
    @Test
    void scn015A1ConcurrentOverlappingSeriesCannotBothSucceed() throws Exception {
        Long resourceId = createRoom("INT-015A1");
        assertTrue(storedReservations(resourceId).isEmpty(),
                "the fixture room must be free before the two concurrent series are fired");

        List<Attempt> attempts = runConcurrently(
                () -> performSeries(resourceId),
                () -> performSeries(resourceId));

        long successes = attempts.stream().filter(Attempt::succeeded).count();
        assertTrue(successes <= 1,
                "the two concurrent overlapping series requests cannot both succeed, but " + describe(attempts));

        /*
         * A successful three-occurrence series must leave exactly its own three rows. Combined with
         * the check below, this is the evidence that the request which did not succeed created no
         * reservation of its own (SCN-015#A3).
         */
        List<Reservation> persisted = storedReservations(resourceId);
        assertEquals(successes == 0 ? 0 : SERIES_OCCURRENCES, persisted.size(),
                "only an accepted three-occurrence series may persist rows; the request that did not "
                        + "succeed must create no reservation, but the committed store holds " + persisted.size()
                        + " row(s): " + describeRows(persisted));
    }

    /**
     * SCN-015#A2 (integration) - Any rows persisted by the two concurrent requests all carry one
     * and the same non-null series identifier, i.e. they belong to at most one of the two series.
     */
    @Test
    void scn015A2PersistedRowsBelongToAtMostOneSeries() throws Exception {
        Long resourceId = createRoom("INT-015A2");
        assertTrue(storedReservations(resourceId).isEmpty(),
                "the fixture room must be free before the two concurrent series are fired");

        List<Attempt> attempts = runConcurrently(
                () -> performSeries(resourceId),
                () -> performSeries(resourceId));

        List<Reservation> persisted = storedReservations(resourceId);
        assertTrue(persisted.stream().allMatch(reservation -> reservation.getSeriesId() != null),
                "every row persisted by the series requests must carry a series identifier, but stored "
                        + persisted.stream().map(Reservation::getSeriesId).collect(Collectors.toList()));

        Set<UUID> distinctSeriesIds = persisted.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(distinctSeriesIds.size() <= 1,
                "rows persisted by the two concurrent requests must belong to at most one of the two "
                        + "series, but found series identifiers " + distinctSeriesIds
                        + " after " + describe(attempts));
    }

    /**
     * SCN-016#A1 (integration) - One single reservation and one overlapping three-occurrence series
     * released at the same instant against one room cannot both succeed.
     */
    @Test
    void scn016A1ConcurrentOneOffAndSeriesCannotBothSucceed() throws Exception {
        Long resourceId = createRoom("INT-016A1");
        assertTrue(storedReservations(resourceId).isEmpty(),
                "the fixture room must be free before the one-off and the series are fired");

        List<Attempt> attempts = runConcurrently(
                () -> performOneOff(resourceId),
                () -> performSeries(resourceId));

        long successes = attempts.stream().filter(Attempt::succeeded).count();
        assertTrue(successes <= 1,
                "the one-off request and the overlapping series request cannot both succeed, but "
                        + describe(attempts));

        boolean oneOffSucceeded = attempts.get(0).succeeded();
        boolean seriesSucceeded = attempts.get(1).succeeded();
        List<Reservation> persisted = storedReservations(resourceId);
        int expectedRows = (oneOffSucceeded ? 1 : 0) + (seriesSucceeded ? SERIES_OCCURRENCES : 0);
        assertEquals(expectedRows, persisted.size(),
                "only the accepted request may leave reservations behind, but the committed store holds "
                        + persisted.size() + " row(s): " + describeRows(persisted));
        if (seriesSucceeded) {
            assertTrue(persisted.stream().allMatch(reservation -> reservation.getSeriesId() != null),
                    "the accepted series must persist its occurrences with a series identifier");
        }
    }

    // ------------------------------------------------------------------
    // Concurrent execution
    // ------------------------------------------------------------------

    /**
     * Submits both callables to a two-thread pool, releases them together through a barrier and
     * waits for both with an explicit timeout. A caller that never completes or throws is recorded
     * as a non-successful attempt rather than silently ignored, and its failure is surfaced in the
     * assertion messages.
     */
    private List<Attempt> runConcurrently(Callable<Attempt> first, Callable<Attempt> second) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Attempt> firstFuture = pool.submit(() -> {
                barrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return first.call();
            });
            Future<Attempt> secondFuture = pool.submit(() -> {
                barrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(await(firstFuture), await(secondFuture));
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt await(Future<Attempt> future) {
        try {
            return future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return new Attempt(false, "concurrent caller did not produce a result", failure);
        }
    }

    private Attempt performSeries(Long resourceId) {
        try {
            MvcResult result = mockMvc.perform(seriesRequest(resourceId)).andReturn();
            int httpStatus = result.getResponse().getStatus();
            boolean accepted = httpStatus >= 200 && httpStatus < 300;
            return new Attempt(accepted, "series POST returned HTTP " + httpStatus, null);
        } catch (Throwable failure) {
            return new Attempt(false, "series POST failed before returning a response", failure);
        }
    }

    private Attempt performOneOff(Long resourceId) {
        try {
            ReservationView view = reservationService.create(
                    new BookingInput(resourceId, "One-off", ONE_OFF_START, ONE_OFF_END), "alice");
            boolean accepted = view != null && view.id() != null;
            return new Attempt(accepted, "one-off reservation was accepted with id "
                    + (view == null ? "<null>" : view.id()), null);
        } catch (Throwable failure) {
            return new Attempt(false, "one-off reservation was rejected by the write path", failure);
        }
    }

    private static String describe(List<Attempt> attempts) {
        StringBuilder message = new StringBuilder();
        for (Attempt attempt : attempts) {
            message.append('[').append(attempt.succeeded() ? "SUCCEEDED" : "FAILED")
                    .append(": ").append(attempt.detail());
            if (attempt.error() != null) {
                message.append(" cause=").append(attempt.error());
            }
            message.append("] ");
        }
        return message.toString();
    }

    private static String describeRows(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> "id=" + reservation.getId()
                        + " start=" + reservation.getStartsAt()
                        + " status=" + reservation.getStatus()
                        + " series=" + reservation.getSeriesId())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private record Attempt(boolean succeeded, String detail, Throwable error) {
    }

    // ------------------------------------------------------------------
    // Fixtures and reads
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder seriesRequest(Long resourceId) {
        String body = """
                {
                  "resourceId": %d,
                  "title": "Weekly planning",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "timezone": "Europe/Berlin",
                  "frequency": "WEEKLY",
                  "occurrences": %d
                }
                """.formatted(resourceId, SERIES_START, SERIES_END, SERIES_OCCURRENCES);
        return post(SERIES_ENDPOINT)
                .with(user("alice").roles("EMPLOYEE", "MANAGER", "ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long createRoom(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ResourceView room = resourceService.create(new ResourceInput(
                code, "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false));
        assertNotNull(room.id(), "fixture room must be persisted with an identifier");
        fixtureResourceIds.add(room.id());
        return room.id();
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside the
     * persistence contexts that served the concurrent writes, ordered by start instant.
     */
    private List<Reservation> storedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .sorted(Comparator.comparing(Reservation::getStartsAt).thenComparing(Reservation::getId))
                .toList();
    }

    /**
     * Removes the rows created by this test class so the embedded store stays clean for the
     * remaining suites; fixture rooms are uniquely coded and never shared between tests.
     */
    @AfterEach
    void removeFixtureReservations() {
        if (fixtureResourceIds.isEmpty()) {
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            for (Long resourceId : fixtureResourceIds) {
                entityManager.createQuery("delete from Reservation r where r.resource.id = :resourceId")
                        .setParameter("resourceId", resourceId)
                        .executeUpdate();
            }
        });
        fixtureResourceIds.clear();
    }

    /**
     * Deterministic clock fixture: the frozen write window (2030) must be in the future for the
     * write paths, independent of the machine clock.
     */
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
