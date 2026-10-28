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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

/**
 * Integration-layer verification of the shared resource-lock discipline for two competing one-off
 * writes (SCN-024, REQ-007).
 *
 * <p>Both writers call the documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)} (the same binding the approved staged
 * concurrent-write test uses for a one-off request) through the real service + JPA stack, released
 * together through a barrier so they really contend for the same room. The room is free and both
 * requests target the identical slot. After both callers have finished, the committed rows are read
 * in a fresh transaction.</p>
 *
 * <p>Asserted and nothing more: the two competing one-off requests cannot both succeed, and the
 * committed store holds exactly the rows of the caller that did succeed. The losing caller's
 * specific response is deliberately not asserted, because the frozen contract leaves it open
 * (AMB-006).</p>
 *
 * <p>The contested slot is 2030-06-10 10:00-11:00 Europe/Berlin (08:00Z-09:00Z), in the future
 * relative to the fixed test clock (2026-03-01T00:00Z). The fixture room is uniquely coded.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationConcurrentOneOffBookingIntegrationTest {

    /** The contested one-off slot, identical for both competing writers. */
    private static final Instant SLOT_START = Instant.parse("2030-06-10T08:00:00Z");
    private static final Instant SLOT_END = Instant.parse("2030-06-10T09:00:00Z");

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 60L;

    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-024#A1 (integration) - Two competing one-off reservation requests for the same room and
     * the same slot cannot both succeed; the committed store holds exactly the successful writer's
     * reservation.
     */
    @Test
    void scn024A1ConcurrentOverlappingOneOffRequestsCannotBothSucceed() throws Exception {
        Long resourceId = createRoom("INT-024A1");
        assertTrue(storedReservations(resourceId).isEmpty(),
                "the fixture room must be free before the two competing one-off requests are fired, but "
                        + describe(storedReservations(resourceId)));

        List<Attempt> attempts = runConcurrently(
                () -> attemptOneOff(resourceId, "alice"),
                () -> attemptOneOff(resourceId, "bob"));

        long successes = attempts.stream().filter(Attempt::succeeded).count();
        assertTrue(successes <= 1,
                "two competing one-off reservation requests for the same room and slot cannot both "
                        + "succeed, but " + describeAttempts(attempts));

        List<Reservation> persisted = storedReservations(resourceId);
        assertEquals(successes, persisted.size(),
                "the committed store must hold exactly the reservations of the callers that succeeded, "
                        + "but " + successes + " caller(s) succeeded while the store holds " + describe(persisted)
                        + " after " + describeAttempts(attempts));
        assertTrue(persisted.size() <= 1,
                "two competing one-off requests cannot leave two reservations for the same slot, but "
                        + describe(persisted));

        for (Reservation reservation : persisted) {
            assertEquals(SLOT_START, reservation.getStartsAt(),
                    "the only reservation left in the store must be the contested slot, but "
                            + describe(persisted));
            assertEquals(SLOT_END, reservation.getEndsAt(),
                    "the only reservation left in the store must be the contested slot, but "
                            + describe(persisted));
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

    /**
     * One competing one-off write against the contested slot through the documented binding. A
     * rejection is a legitimate outcome for the losing caller and is recorded, not asserted.
     */
    private Attempt attemptOneOff(Long resourceId, String actor) {
        try {
            ReservationView view = reservationService.create(
                    new BookingInput(resourceId, "One-off " + actor, SLOT_START, SLOT_END), actor);
            boolean accepted = view != null && view.id() != null;
            return new Attempt(accepted, "one-off create accepted with id "
                    + (view == null ? "<null>" : view.id()), null);
        } catch (Throwable failure) {
            return new Attempt(false, "one-off create was rejected by the write path ("
                    + failure.getClass().getSimpleName() + ")", failure);
        }
    }

    private static String describeAttempts(List<Attempt> attempts) {
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

    private record Attempt(boolean succeeded, String detail, Throwable error) {
    }

    // ------------------------------------------------------------------
    // Fixtures and reads
    // ------------------------------------------------------------------

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
     * persistence contexts that served the concurrent writes.
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

    private static String describe(List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            return "stored={}";
        }
        return reservations.stream()
                .map(reservation -> "[id=" + reservation.getId()
                        + ", startsAt=" + reservation.getStartsAt()
                        + ", endsAt=" + reservation.getEndsAt()
                        + ", bookedBy=" + reservation.getBookedBy() + "]")
                .collect(Collectors.joining(", ", "stored={", "}"));
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
     * Deterministic clock fixture: the contested slot (2030) must be in the future for the write
     * path, independent of the machine clock.
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
