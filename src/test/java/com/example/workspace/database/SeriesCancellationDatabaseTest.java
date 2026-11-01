package com.example.workspace.database;

import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Database-layer proofs for SCN-014 (REQ-008).
 *
 * The precondition (a 3-occurrence series for an active resource) is materialised as three committed rows
 * sharing one seriesId, which is exactly the durable state the scenario starts from. The existing
 * cancellation endpoint is then invoked for the second occurrence only, and the resulting durable state is
 * read back through the repository. Two independent native cases cover the state transition (A1) and the
 * preserved series identity (A2).
 */
@SpringBootTest
class SeriesCancellationDatabaseTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    private Long fixtureResourceId;
    private Instant windowStart;
    private Instant windowEnd;
    private final List<Long> fixtureReservationIds = new ArrayList<>();

    @Test
    void cancellingSecondOccurrenceChangesOnlyThatOccurrence() {
        // Occurrences: 2031-06-02 / 06-09 / 06-16 at 09:00-10:00 Europe/Berlin (CEST, +02:00).
        useWindow("2031-06-02T07:00:00Z", "2031-06-16T08:00:00Z");
        BookableResource room = persistRoom("WB014A");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId, "2031-06-02T07:00:00Z", "2031-06-02T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId, "2031-06-09T07:00:00Z", "2031-06-09T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId, "2031-06-16T07:00:00Z", "2031-06-16T08:00:00Z");

        reservationService.cancel(second.getId(), owner());

        assertEquals(3, storedInWindow().size(),
                "cancelling one occurrence must not add or remove rows; stored rows: " + describe(storedInWindow()));

        Reservation reloadedFirst = reload(first);
        Reservation reloadedSecond = reload(second);
        Reservation reloadedThird = reload(third);

        assertEquals(ReservationStatus.CANCELLED, reloadedSecond.getStatus(),
                "the cancelled occurrence must be stored as CANCELLED");
        assertEquals(ReservationStatus.CONFIRMED, reloadedFirst.getStatus(),
                "the first occurrence must keep its unchanged state");
        assertEquals(ReservationStatus.CONFIRMED, reloadedThird.getStatus(),
                "the third occurrence must keep its unchanged state");

        assertUnchanged(first, reloadedFirst);
        assertUnchanged(third, reloadedThird);

        assertEquals(second.getStartsAt(), reloadedSecond.getStartsAt(),
                "cancelling the occurrence must not move its start time");
        assertEquals(second.getEndsAt(), reloadedSecond.getEndsAt(),
                "cancelling the occurrence must not move its end time");
        assertEquals(second.getTitle(), reloadedSecond.getTitle(),
                "cancelling the occurrence must not change its title");
        assertEquals(second.getBookedBy(), reloadedSecond.getBookedBy(),
                "cancelling the occurrence must not change its owner");
        assertEquals(seriesId, reloadedSecond.getSeriesId(),
                "cancelling the occurrence must not change its series identifier");
    }

    @Test
    void remainingOccurrencesKeepTheirSeriesIdentifierAfterCancellation() {
        useWindow("2031-06-02T07:00:00Z", "2031-06-16T08:00:00Z");
        BookableResource room = persistRoom("WB014B");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId, "2031-06-02T07:00:00Z", "2031-06-02T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId, "2031-06-09T07:00:00Z", "2031-06-09T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId, "2031-06-16T07:00:00Z", "2031-06-16T08:00:00Z");

        reservationService.cancel(second.getId(), owner());

        // Guard: the cancellation must actually have taken effect, otherwise the seriesId assertion below
        // could pass on a no-op implementation.
        assertEquals(ReservationStatus.CANCELLED, reload(second).getStatus(),
                "the cancellation of the second occurrence must be persisted before checking the survivors");

        assertEquals(3, storedInWindow().size(),
                "cancelling one occurrence must not add or remove rows; stored rows: " + describe(storedInWindow()));

        Reservation reloadedFirst = reload(first);
        Reservation reloadedThird = reload(third);

        assertNotNull(reloadedFirst.getSeriesId(),
                "the first occurrence must keep a non-null series identifier after the cancellation");
        assertNotNull(reloadedThird.getSeriesId(),
                "the third occurrence must keep a non-null series identifier after the cancellation");
        assertEquals(seriesId, reloadedFirst.getSeriesId(),
                "the first occurrence must keep the series identifier of the cancelled series");
        assertEquals(seriesId, reloadedThird.getSeriesId(),
                "the third occurrence must keep the series identifier of the cancelled series");
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

    private Reservation persistOccurrence(BookableResource room, UUID seriesId, String startsAt, String endsAt) {
        Reservation reservation = new Reservation(room, "alice", "WB014 series occurrence",
                Instant.parse(startsAt), Instant.parse(endsAt), ReservationStatus.CONFIRMED, Instant.now());
        reservation.setSeriesId(seriesId);
        Reservation saved = reservationRepository.save(reservation);
        assertNotNull(saved.getId(), "the occurrence fixture must be persisted with an id");
        fixtureReservationIds.add(saved.getId());
        return saved;
    }

    private Reservation reload(Reservation reservation) {
        return reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new AssertionError(
                        "reservation " + reservation.getId() + " disappeared from the database"));
    }

    private static void assertUnchanged(Reservation before, Reservation after) {
        assertEquals(before.getId(), after.getId(), "reservation identity changed");
        assertEquals(before.getStartsAt(), after.getStartsAt(),
                "startsAt changed for reservation " + before.getId());
        assertEquals(before.getEndsAt(), after.getEndsAt(),
                "endsAt changed for reservation " + before.getId());
        assertEquals(before.getTitle(), after.getTitle(),
                "title changed for reservation " + before.getId());
        assertEquals(before.getBookedBy(), after.getBookedBy(),
                "bookedBy changed for reservation " + before.getId());
        assertEquals(before.getStatus(), after.getStatus(),
                "status changed for reservation " + before.getId());
        assertEquals(before.getSeriesId(), after.getSeriesId(),
                "seriesId changed for reservation " + before.getId());
    }

    private static UsernamePasswordAuthenticationToken owner() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String describe(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " status=" + row.getStatus() + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
