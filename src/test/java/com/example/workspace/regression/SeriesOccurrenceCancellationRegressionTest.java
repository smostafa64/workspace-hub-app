package com.example.workspace.regression;

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
 * Regression-layer proofs for SCN-014#A1 and SCN-014#A2 (REQ-008, SE-001, SE-008).
 *
 * The precondition (a 3-occurrence series for an active resource, with the caller allowed to cancel) is
 * materialised as three committed rows sharing one series identifier, which is exactly the durable state
 * the scenario starts from. The second occurrence is then cancelled through the pre-existing
 * reservation endpoint, invoked on the real {@link ReservationController} bean of the running application
 * context with a real {@link Authentication}. The call runs through the real service and datasource, so
 * the reused cancellation flow is what is being re-run; no series endpoint is used.
 *
 * The outcome is then read back per occurrence from the repository, independently of the response. A1 is
 * observed by the first case: only the second occurrence transitions to CANCELLED, while the first and
 * third keep their state, times, title and owner. A2 is observed by the second case: the two surviving
 * occurrences keep their non-null series identifier, with a persisted-cancellation guard so the check
 * cannot pass on a no-op implementation. The cases use different future months and unique resources.
 */
@SpringBootTest
class SeriesOccurrenceCancellationRegressionTest {

    private static final String OCCURRENCE_TITLE = "WB014 regression series occurrence";

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

    /**
     * SCN-014#A1: only the cancelled occurrence changes state; the first and third occurrences remain
     * unchanged.
     */
    @Test
    void cancellingSecondOccurrenceLeavesFirstAndThirdUnchanged() {
        // Occurrences: 2032-07-05 / 07-12 / 07-19 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2032-07-05T06:00:00Z", "2032-07-19T09:00:00Z");
        BookableResource room = persistRoom("WB014R1");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId,
                "2032-07-05T07:00:00Z", "2032-07-05T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId,
                "2032-07-12T07:00:00Z", "2032-07-12T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId,
                "2032-07-19T07:00:00Z", "2032-07-19T08:00:00Z");

        ReservationView cancelled = reservationController.cancel(second.getId(), alice());

        assertNotNull(cancelled,
                "cancelling one occurrence through the existing endpoint must return the reservation");
        assertEquals(second.getId(), cancelled.id(),
                "the cancellation response must identify the cancelled occurrence");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the cancelled occurrence must be reported as CANCELLED");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "cancelling one occurrence must neither add nor remove rows, but the stored rows are: "
                        + describeRows(stored));

        Reservation reloadedFirst = reload(first);
        Reservation reloadedSecond = reload(second);
        Reservation reloadedThird = reload(third);

        assertEquals(ReservationStatus.CANCELLED, reloadedSecond.getStatus(),
                "the cancelled occurrence must be stored as CANCELLED, but it was "
                        + reloadedSecond.getStatus() + "; stored rows: " + describeRows(stored));
        assertEquals(second.getStartsAt(), reloadedSecond.getStartsAt(),
                "cancelling the occurrence must not move its start time");
        assertEquals(second.getEndsAt(), reloadedSecond.getEndsAt(),
                "cancelling the occurrence must not move its end time");

        assertEquals(ReservationStatus.CONFIRMED, reloadedFirst.getStatus(),
                "the first occurrence must remain unchanged after the single-occurrence cancellation, "
                        + "but it was " + reloadedFirst.getStatus() + "; stored rows: "
                        + describeRows(stored));
        assertEquals(ReservationStatus.CONFIRMED, reloadedThird.getStatus(),
                "the third occurrence must remain unchanged after the single-occurrence cancellation, "
                        + "but it was " + reloadedThird.getStatus() + "; stored rows: "
                        + describeRows(stored));

        assertUnchanged(first, reloadedFirst);
        assertUnchanged(third, reloadedThird);
    }

    /**
     * SCN-014#A2: the remaining occurrences keep their series identifier after the single-occurrence
     * cancellation.
     */
    @Test
    void survivingOccurrencesKeepTheirSeriesIdentifier() {
        // Occurrences: 2032-08-02 / 08-09 / 08-16 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2032-08-02T06:00:00Z", "2032-08-16T09:00:00Z");
        BookableResource room = persistRoom("WB014R2");
        UUID seriesId = UUID.randomUUID();
        Reservation first = persistOccurrence(room, seriesId,
                "2032-08-02T07:00:00Z", "2032-08-02T08:00:00Z");
        Reservation second = persistOccurrence(room, seriesId,
                "2032-08-09T07:00:00Z", "2032-08-09T08:00:00Z");
        Reservation third = persistOccurrence(room, seriesId,
                "2032-08-16T07:00:00Z", "2032-08-16T08:00:00Z");

        ReservationView cancelled = reservationController.cancel(second.getId(), alice());

        assertNotNull(cancelled,
                "cancelling one occurrence through the existing endpoint must return the reservation");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the cancelled occurrence must be reported as CANCELLED");

        // Guard: the cancellation must have been persisted, otherwise the survivor checks below could
        // pass on a no-op implementation.
        assertEquals(ReservationStatus.CANCELLED, reload(second).getStatus(),
                "the cancellation of the second occurrence must be persisted before the survivors are "
                        + "checked");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "cancelling one occurrence must neither add nor remove rows, but the stored rows are: "
                        + describeRows(stored));

        Reservation reloadedFirst = reload(first);
        Reservation reloadedThird = reload(third);

        assertNotNull(reloadedFirst.getSeriesId(),
                "the first surviving occurrence must keep a non-null series identifier after the "
                        + "cancellation, but the stored rows are: " + describeRows(stored));
        assertNotNull(reloadedThird.getSeriesId(),
                "the third surviving occurrence must keep a non-null series identifier after the "
                        + "cancellation, but the stored rows are: " + describeRows(stored));
        assertEquals(seriesId, reloadedFirst.getSeriesId(),
                "the first surviving occurrence must keep the series identifier of the cancellation's "
                        + "series, but got " + reloadedFirst.getSeriesId() + "; stored rows: "
                        + describeRows(stored));
        assertEquals(seriesId, reloadedThird.getSeriesId(),
                "the third surviving occurrence must keep the series identifier of the cancellation's "
                        + "series, but got " + reloadedThird.getSeriesId() + "; stored rows: "
                        + describeRows(stored));
    }

    private Reservation persistOccurrence(BookableResource room, UUID seriesId, String startsAt,
                                          String endsAt) {
        Reservation reservation = new Reservation(room, "alice", OCCURRENCE_TITLE,
                Instant.parse(startsAt), Instant.parse(endsAt), ReservationStatus.CONFIRMED,
                Instant.now());
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
                code, "Regression fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, false));
        fixtureResourceId = saved.getId();
        assertNotNull(fixtureResourceId, "the resource fixture must be persisted with an id");
        return saved;
    }

    private static void assertUnchanged(Reservation before, Reservation after) {
        assertEquals(before.getId(), after.getId(), "reservation identity changed");
        assertEquals(before.getStartsAt(), after.getStartsAt(),
                "startsAt changed for surviving reservation " + before.getId());
        assertEquals(before.getEndsAt(), after.getEndsAt(),
                "endsAt changed for surviving reservation " + before.getId());
        assertEquals(before.getTitle(), after.getTitle(),
                "title changed for surviving reservation " + before.getId());
        assertEquals(before.getBookedBy(), after.getBookedBy(),
                "owner changed for surviving reservation " + before.getId());
        assertEquals(before.getSeriesId(), after.getSeriesId(),
                "seriesId changed for surviving reservation " + before.getId());
    }

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus()
                        + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
