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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer proof for SCN-007#A2 (REQ-005, REQ-006).
 *
 * The scenario is executed across the real Spring boundaries that the frozen scenario exercises: the
 * real {@link ReservationController} bean of the running application context is invoked with a real
 * {@link Authentication}, and the request then runs through the real transactional service and the
 * configured datasource. No service is mocked and no response body is used as persistence evidence.
 *
 * The rejected 3-occurrence series overlaps only its second occurrence, so the frozen assertion is that
 * nothing from the series survives, including the first and third occurrences that conflict with nothing.
 * The 409 carrier is asserted first so a rejection for an unrelated reason cannot stand in for the
 * conflict; the identical request is then replayed with the overlapping reservation removed, which is the
 * nearby valid control proving that only the overlap caused the rejection.
 */
@SpringBootTest
class SeriesConflictRollbackIntegrationTest {

    private static final Instant FIRST_OCCURRENCE_START = Instant.parse("2032-02-02T08:00:00Z");
    private static final Instant SECOND_OCCURRENCE_START = Instant.parse("2032-02-09T08:00:00Z");
    private static final Instant THIRD_OCCURRENCE_START = Instant.parse("2032-02-16T08:00:00Z");

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
    void conflictOnSecondOccurrenceLeavesNoSeriesRowsBehind() throws Exception {
        // Occurrences: 2032-02-02 / 02-09 / 02-16 at 09:00-10:00 Europe/Berlin (CET, +01:00 in February)
        // => 08:00-09:00Z. The existing reservation overlaps only the second occurrence (08:30-09:30Z).
        useWindow("2032-02-02T07:00:00Z", "2032-02-16T09:00:00Z");
        BookableResource room = persistRoom("WB007I", false);
        Reservation oneOff = persistReservation(room, "2032-02-09T08:30:00Z", "2032-02-09T09:30:00Z",
                ReservationStatus.CONFIRMED, null);

        ReservationSeriesInput input = seriesInput(room, "WB007 integration series",
                "2032-02-02T09:00:00+01:00", "2032-02-02T10:00:00+01:00", 3);

        assertSeriesRejectedWithConflict(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "the rejected 3-occurrence series must not persist any occurrence, but the stored rows are: "
                        + describe(stored));
        assertEquals(oneOff.getId(), stored.get(0).getId(),
                "the pre-existing reservation must be the only surviving row, but the stored rows are: "
                        + describe(stored));
        assertTrue(stored.stream().noneMatch(row -> row.getSeriesId() != null),
                "a reservation carrying a series identifier survived the rejected series: " + describe(stored));
        assertTrue(stored.stream().noneMatch(row -> FIRST_OCCURRENCE_START.equals(row.getStartsAt())),
                "the first (non-conflicting) occurrence was persisted despite the rejected series: "
                        + describe(stored));
        assertTrue(stored.stream().noneMatch(row -> THIRD_OCCURRENCE_START.equals(row.getStartsAt())),
                "the third (non-conflicting) occurrence was persisted despite the rejected series: "
                        + describe(stored));

        // Nearby valid control: the same request differs only by the overlapping reservation, so it must be
        // created once that row is gone. This attributes the 409 above to the overlap and nothing else.
        reservationRepository.deleteById(oneOff.getId());
        submitSeries(input);
        List<Reservation> afterControl = storedInWindow();
        assertEquals(3, afterControl.size(),
                "the identical request must create its three occurrences once the overlapping reservation is "
                        + "removed; stored rows: " + describe(afterControl));
        assertEquals(Set.of(FIRST_OCCURRENCE_START, SECOND_OCCURRENCE_START, THIRD_OCCURRENCE_START),
                startsOf(afterControl),
                "the created occurrences must be the three requested weekly instants; stored rows: "
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
        Reservation reservation = new Reservation(room, "alice", "WB007 existing reservation",
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
