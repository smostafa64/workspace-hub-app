package com.example.workspace.integration;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationController;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer proofs for SCN-008#A1 and SCN-008#A2 (REQ-005, REQ-006).
 *
 * Both cases drive the real {@link ReservationController} bean of the running application context with a
 * real {@link Authentication}; the request executes through the real transactional service and the
 * configured datasource, and the durable outcome is read back through the repository instead of being
 * inferred from the rejection.
 *
 * The two frozen assertions observe different facts and therefore have their own native cases: A1 states
 * that the rejected series creates no reservation at all, A2 states that the pre-existing one-off row is
 * the only survivor. Each case uses its own future week and its own resource, so neither can observe the
 * other's rows. Each case also replays the identical request after removing only the overlapping
 * reservation, which proves the 409 (asserted first) was caused by the overlap and not by an unrelated
 * input fault.
 */
@SpringBootTest
class OneOffConflictRollbackIntegrationTest {

    private static final Instant SERIES_FIRST_START = Instant.parse("2032-03-01T08:00:00Z");
    private static final Instant SERIES_LAST_START = Instant.parse("2032-03-08T08:00:00Z");

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

    @BeforeEach
    void authenticateCaller() {
        SecurityContextHolder.getContext().setAuthentication(alice());
    }

    @Test
    void overlapWithOneOffOnLastOccurrenceCreatesNoSeriesRows() throws Exception {
        // Occurrences: 2032-03-01 / 03-08 at 09:00-10:00 Europe/Berlin (CET, +01:00) => 08:00-09:00Z.
        // The one-off overlaps only the last occurrence (07:30-08:30Z on 2032-03-08).
        useWindow("2032-03-01T07:00:00Z", "2032-03-08T09:00:00Z");
        BookableResource room = persistRoom("WB008I", false);
        Reservation oneOff = persistReservation(room, "2032-03-08T07:30:00Z", "2032-03-08T08:30:00Z",
                ReservationStatus.CONFIRMED, null);

        ReservationSeriesInput input = seriesInput(room, "WB008 integration series",
                "2032-03-01T09:00:00+01:00", "2032-03-01T10:00:00+01:00", 2);

        assertSeriesRejectedWithConflict(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "the rejected series must not create any reservation, but the stored rows are: "
                        + describe(stored));
        assertEquals(oneOff.getId(), stored.get(0).getId(),
                "the pre-existing one-off reservation must be the only stored row, but the stored rows are: "
                        + describe(stored));
        assertTrue(stored.stream().noneMatch(row -> row.getSeriesId() != null),
                "a reservation carrying a series identifier was created for the rejected series: "
                        + describe(stored));
        assertTrue(stored.stream().noneMatch(row -> SERIES_FIRST_START.equals(row.getStartsAt())),
                "the first (non-conflicting) occurrence was created for the rejected series: "
                        + describe(stored));

        // Nearby valid control: only the overlapping one-off differs, so this exact request must create
        // its two occurrences once that row is removed.
        reservationRepository.deleteById(oneOff.getId());
        submitSeries(input);
        List<Reservation> afterControl = storedInWindow();
        assertEquals(2, afterControl.size(),
                "the identical request must create its two occurrences once the overlapping one-off is "
                        + "removed; stored rows: " + describe(afterControl));
        assertEquals(Set.of(SERIES_FIRST_START, SERIES_LAST_START), startsOf(afterControl),
                "the created occurrences must be the two requested weekly instants; stored rows: "
                        + describe(afterControl));
    }

    @Test
    void rejectedOneOffOverlapLeavesOnlyThePreExistingReservation() throws Exception {
        // Occurrences: 2032-03-15 / 03-22 at 09:00-10:00 Europe/Berlin (CET, +01:00) => 08:00-09:00Z.
        // The one-off overlaps only the last occurrence (07:30-08:30Z on 2032-03-22).
        useWindow("2032-03-15T07:00:00Z", "2032-03-22T09:00:00Z");
        BookableResource room = persistRoom("WB008J", false);
        Reservation oneOff = persistReservation(room, "2032-03-22T07:30:00Z", "2032-03-22T08:30:00Z",
                ReservationStatus.CONFIRMED, null);

        ReservationSeriesInput input = seriesInput(room, "WB008 integration series",
                "2032-03-15T09:00:00+01:00", "2032-03-15T10:00:00+01:00", 2);

        assertSeriesRejectedWithConflict(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "only the pre-existing one-off reservation may remain stored after the rejected request, "
                        + "but the stored rows are: " + describe(stored));

        Reservation survivor = stored.get(0);
        assertEquals(oneOff.getId(), survivor.getId(),
                "the surviving row must be the pre-existing one-off reservation, but it was: "
                        + describe(stored));
        assertSameBooking(oneOff, survivor, "the pre-existing one-off reservation");
        assertNull(survivor.getSeriesId(),
                "the pre-existing one-off reservation must keep a null series identifier, but it was "
                        + survivor.getSeriesId() + "; stored rows: " + describe(stored));

        // Nearby valid control: removing only the overlapping row must let the identical request through.
        reservationRepository.deleteById(oneOff.getId());
        submitSeries(input);
        List<Reservation> afterControl = storedInWindow();
        assertEquals(2, afterControl.size(),
                "the identical request must create its two occurrences once the overlapping one-off is "
                        + "removed; stored rows: " + describe(afterControl));
        assertEquals(Set.of(Instant.parse("2032-03-15T08:00:00Z"), Instant.parse("2032-03-22T08:00:00Z")),
                startsOf(afterControl),
                "the created occurrences must be the two requested weekly instants; stored rows: "
                        + describe(afterControl));
    }

    private void assertSeriesRejectedWithConflict(ReservationSeriesInput input) {
        ResponseEntity<?> response;
        try {
            response = reservationController.createSeries(input, alice());
        } catch (RuntimeException thrown) {
            assertTrue(isConflictCarrier(thrown),
                    "the series must be rejected through the frozen 409 carrier, but it failed with: " + thrown);
            return;
        }
        assertNotNull(response, "the series submission must not return a null response");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                "the overlapping series must be rejected with status 409, but got " + response.getStatusCode());
    }

    private ResponseEntity<?> submitSeries(ReservationSeriesInput input) {
        ResponseEntity<?> response = reservationController.createSeries(input, alice());
        assertNotNull(response, "the series creation must return a response");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "the conflict-free series must be accepted (the exact success code is not frozen, AMB-009), "
                        + "but the response status was " + response.getStatusCode());
        return response;
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

    private BookableResource persistRoom(String codePrefix, boolean approvalRequired) {
        String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        BookableResource saved = resourceRepository.save(new BookableResource(
                code, "Integration fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, approvalRequired));
        fixtureResourceId = saved.getId();
        assertNotNull(fixtureResourceId, "the resource fixture must be persisted with an id");
        return saved;
    }

    private Reservation persistReservation(BookableResource room, String startsAt, String endsAt,
                                           ReservationStatus status, UUID seriesId) {
        Reservation reservation = new Reservation(room, "alice", "WB008 existing one-off reservation",
                Instant.parse(startsAt), Instant.parse(endsAt), status, Instant.now());
        if (seriesId != null) {
            reservation.setSeriesId(seriesId);
        }
        Reservation saved = reservationRepository.save(reservation);
        assertNotNull(saved.getId(), "the reservation fixture must be persisted with an id");
        fixtureReservationIds.add(saved.getId());
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
