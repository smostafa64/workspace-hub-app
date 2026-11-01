package com.example.workspace.integration;

import com.example.workspace.api.ReservationController;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration-layer proofs for SCN-014#A1 and SCN-014#A2 (REQ-008).
 *
 * The precondition is a committed 3-occurrence series sharing one series identifier, materialised exactly
 * as the scenario starts from. The second occurrence is then cancelled through the existing reservation
 * endpoint invoked on the real {@link ReservationController} bean of the running application context with a
 * real {@link Authentication}; the call runs through the real service and datasource. The resulting state
 * is read back per occurrence through the repository, so the narrow scope of the change is observed
 * durably and not from the response alone.
 *
 * A1 observes that only the cancelled occurrence changes state; A2 observes that the two surviving
 * occurrences keep their non-null series identifier. Each case owns its future weeks and resource. The
 * precondition grants the caller the right to cancel, so the owner identity is used; no undocumented
 * authorization rule is asserted (AMB-006).
 */
@SpringBootTest
class SeriesCancellationIntegrationTest {

    @Autowired
    private ReservationController reservationController;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    private Long fixtureResourceId;
    private Instant windowStart;
    private Instant windowEnd;
    private final List<Long> fixtureReservationIds = new ArrayList<>();

    @BeforeEach
    void authenticateCaller() {
        SecurityContextHolder.getContext().setAuthentication(alice());
    }

    @Test
    void cancellingSecondOccurrenceLeavesFirstAndThirdUnchanged() {
        // Occurrences: 2032-05-03 / 05-10 / 05-17 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2032-05-03T06:00:00Z", "2032-05-17T09:00:00Z");
        BookableResource room = persistRoom("WB014I");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId, "2032-05-03T07:00:00Z", "2032-05-03T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId, "2032-05-10T07:00:00Z", "2032-05-10T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId, "2032-05-17T07:00:00Z", "2032-05-17T08:00:00Z");

        ReservationView cancelled = reservationController.cancel(second.getId(), alice());
        assertNotNull(cancelled, "cancelling one occurrence must return the cancelled reservation");
        assertEquals(second.getId(), cancelled.id(),
                "the cancellation response must identify the cancelled occurrence");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the cancelled occurrence must be reported as CANCELLED");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "cancelling one occurrence must neither add nor remove rows, but the stored rows are: "
                        + describe(stored));

        Reservation reloadedSecond = reload(second);
        assertEquals(ReservationStatus.CANCELLED, reloadedSecond.getStatus(),
                "the cancelled occurrence must be stored as CANCELLED, but it was "
                        + reloadedSecond.getStatus() + "; stored rows: " + describe(stored));
        assertEquals(second.getStartsAt(), reloadedSecond.getStartsAt(),
                "cancelling the occurrence must not move its start time");
        assertEquals(second.getEndsAt(), reloadedSecond.getEndsAt(),
                "cancelling the occurrence must not move its end time");

        Reservation reloadedFirst = reload(first);
        Reservation reloadedThird = reload(third);
        assertEquals(ReservationStatus.CONFIRMED, reloadedFirst.getStatus(),
                "the first occurrence must remain CONFIRMED after the single-occurrence cancellation, but it "
                        + "was " + reloadedFirst.getStatus() + "; stored rows: " + describe(stored));
        assertEquals(ReservationStatus.CONFIRMED, reloadedThird.getStatus(),
                "the third occurrence must remain CONFIRMED after the single-occurrence cancellation, but it "
                        + "was " + reloadedThird.getStatus() + "; stored rows: " + describe(stored));
        assertSameBooking(first, reloadedFirst, "the first occurrence");
        assertSameBooking(third, reloadedThird, "the third occurrence");
    }

    @Test
    void survivingOccurrencesKeepTheirSeriesIdentifier() {
        // Occurrences: 2032-05-24 / 05-31 / 06-07 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2032-05-24T06:00:00Z", "2032-06-07T09:00:00Z");
        BookableResource room = persistRoom("WB014J");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId, "2032-05-24T07:00:00Z", "2032-05-24T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId, "2032-05-31T07:00:00Z", "2032-05-31T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId, "2032-06-07T07:00:00Z", "2032-06-07T08:00:00Z");

        ReservationView cancelled = reservationController.cancel(second.getId(), alice());
        assertNotNull(cancelled, "cancelling one occurrence must return the cancelled reservation");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the cancelled occurrence must be reported as CANCELLED");

        // Guard: the cancellation must have been persisted, otherwise the survivor checks below could pass
        // on a no-op implementation.
        assertEquals(ReservationStatus.CANCELLED, reload(second).getStatus(),
                "the cancellation of the second occurrence must be persisted before the survivors are checked");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "cancelling one occurrence must neither add nor remove rows, but the stored rows are: "
                        + describe(stored));

        Reservation reloadedFirst = reload(first);
        Reservation reloadedThird = reload(third);
        assertNotNull(reloadedFirst.getSeriesId(),
                "the first occurrence must keep a non-null series identifier after the cancellation, but the "
                        + "stored rows are: " + describe(stored));
        assertNotNull(reloadedThird.getSeriesId(),
                "the third occurrence must keep a non-null series identifier after the cancellation, but the "
                        + "stored rows are: " + describe(stored));
        assertEquals(seriesId, reloadedFirst.getSeriesId(),
                "the first occurrence must keep the series identifier of the cancelled series, but got "
                        + reloadedFirst.getSeriesId() + "; stored rows: " + describe(stored));
        assertEquals(seriesId, reloadedThird.getSeriesId(),
                "the third occurrence must keep the series identifier of the cancelled series, but got "
                        + reloadedThird.getSeriesId() + "; stored rows: " + describe(stored));
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

    private static void assertSameBooking(Reservation expected, Reservation actual, String label) {
        assertEquals(expected.getId(), actual.getId(), label + " identity changed");
        assertEquals(expected.getStartsAt(), actual.getStartsAt(), label + " start time changed");
        assertEquals(expected.getEndsAt(), actual.getEndsAt(), label + " end time changed");
        assertEquals(expected.getTitle(), actual.getTitle(), label + " title changed");
        assertEquals(expected.getBookedBy(), actual.getBookedBy(), label + " owner changed");
        assertEquals(expected.getStatus(), actual.getStatus(), label + " status changed");
        assertEquals(expected.getSeriesId(), actual.getSeriesId(), label + " series identifier changed");
    }

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String describe(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus()
                        + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
