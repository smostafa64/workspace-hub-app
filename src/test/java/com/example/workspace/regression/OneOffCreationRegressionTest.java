package com.example.workspace.regression;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationController;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression-layer proofs for SCN-016#A1 and SCN-016#A2 (REQ-007, REQ-012, SE-002).
 *
 * Both cases drive the pre-existing one-off creation path through the real
 * {@link ReservationController} bean of the running application context with a real
 * {@link Authentication}; the call crosses the real controller and service and commits through the
 * configured datasource. That is the flow the scenario re-runs: no series creation is involved and no
 * repository, service or mapper is mocked.
 *
 * A2 (regression dimension) is observed by the first case: the pre-existing one-off creation endpoint
 * still returns a success response carrying the created reservation, and the reservation is really
 * stored. The exact success status code is pre-existing behaviour and is therefore not pinned; success
 * is 2xx with a persisted row, so a status-only observation cannot pass.
 *
 * A1 is observed by the second case in two independently read places that the assertion names: the
 * creation response reports a null seriesId, and the committed row is stored with a null series
 * identifier. Each case uses its own future week and its own unique resource, and every fixture id and
 * row is registered for cleanup before any assertion can fail.
 *
 * Nothing about approval outcomes or other pre-existing booking rules is asserted: that content is not
 * frozen (AMB-010). The fixture resource is active and does not require approval, as the precondition
 * states.
 */
@SpringBootTest
class OneOffCreationRegressionTest {

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
     * SCN-016#A2: the pre-existing one-off creation endpoint still creates a reservation successfully
     * after the reservation response was extended with seriesId.
     */
    @Test
    void preExistingOneOffCreationEndpointStillCreatesReservation() {
        // One-off: 2031-11-03 08:00-09:00 Europe/Berlin (CET, +01:00) => 07:00-08:00Z.
        useWindow("2031-11-03T06:00:00Z", "2031-11-03T09:00:00Z");
        BookableResource room = persistRoom("WB016R1");

        Instant start = Instant.parse("2031-11-03T07:00:00Z");
        Instant end = Instant.parse("2031-11-03T08:00:00Z");
        String title = "WB016 regression one-off";
        BookingInput input = new BookingInput(room.getId(), title, start, end);

        ResponseEntity<ReservationView> response = reservationController.create(input, alice());

        assertNotNull(response, "the pre-existing one-off creation endpoint must return a response");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "the pre-existing one-off creation endpoint must still succeed after the reservation "
                        + "response is extended with seriesId, but the response status was "
                        + response.getStatusCode());

        ReservationView created = response.getBody();
        assertNotNull(created, "a successful one-off creation must return the created reservation");
        assertNotNull(created.id(), "the created one-off reservation must be reported with an identifier");
        fixtureReservationIds.add(created.id());

        assertEquals(room.getId(), created.resourceId(),
                "the one-off creation response must report the requested resource");
        assertEquals(title, created.title(),
                "the one-off creation response must report the requested title");
        assertEquals(start, created.startsAt(),
                "the one-off creation response must report the requested start instant");
        assertEquals(end, created.endsAt(),
                "the one-off creation response must report the requested end instant");
        assertEquals("alice", created.bookedBy(),
                "the one-off creation response must report the authenticated caller as the booker");

        // Independent storage read: the success response must correspond to a reservation that really
        // exists in the datasource, so a response-only success cannot pass.
        Reservation stored = reservationRepository.findById(created.id())
                .orElseThrow(() -> new AssertionError("the pre-existing one-off creation endpoint reported "
                        + "reservation " + created.id() + " but no such row was stored"));

        assertEquals(title, stored.getTitle(),
                "the stored one-off reservation must keep the requested title");
        assertEquals(start, stored.getStartsAt(),
                "the stored one-off reservation must keep the requested start instant");
        assertEquals(end, stored.getEndsAt(),
                "the stored one-off reservation must keep the requested end instant");
        assertEquals("alice", stored.getBookedBy(),
                "the stored one-off reservation must keep the authenticated caller as the booker");
        assertEquals(created.id(), stored.getId(),
                "the stored row identity must match the reservation reported by the endpoint");

        List<Reservation> rows = storedInWindow();
        assertEquals(1, rows.size(),
                "the one-off creation must store exactly the one requested reservation, but the stored "
                        + "rows are: " + describeRows(rows));
    }

    /**
     * SCN-016#A1: a one-off reservation reports seriesId as null in its response and is stored with a
     * null series identifier.
     */
    @Test
    void oneOffReservationReportsNullSeriesIdInResponseAndStorage() {
        // One-off: 2031-11-10 08:00-09:00 Europe/Berlin (CET, +01:00) => 07:00-08:00Z.
        useWindow("2031-11-10T06:00:00Z", "2031-11-10T09:00:00Z");
        BookableResource room = persistRoom("WB016R2");

        Instant start = Instant.parse("2031-11-10T07:00:00Z");
        Instant end = Instant.parse("2031-11-10T08:00:00Z");
        String title = "WB016 regression one-off seriesId";

        ResponseEntity<ReservationView> response = reservationController.create(
                new BookingInput(room.getId(), title, start, end), alice());

        assertNotNull(response, "the pre-existing one-off creation endpoint must return a response");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "the pre-existing one-off creation endpoint must still succeed for the one-off flow, "
                        + "but the response status was " + response.getStatusCode());

        ReservationView created = response.getBody();
        assertNotNull(created, "a successful one-off creation must return the created reservation");
        assertNotNull(created.id(), "the created one-off reservation must be reported with an identifier");
        fixtureReservationIds.add(created.id());

        assertNull(created.seriesId(),
                "a one-off reservation must report seriesId as null in its creation response, but the "
                        + "response reported " + created.seriesId());

        Reservation stored = reservationRepository.findById(created.id())
                .orElseThrow(() -> new AssertionError("the one-off reservation reported by the endpoint "
                        + "was not stored: id " + created.id()));

        assertNull(stored.getSeriesId(),
                "the one-off reservation must be stored with a null series identifier, but the stored "
                        + "seriesId was " + stored.getSeriesId());

        List<Reservation> rows = storedInWindow();
        assertEquals(1, rows.size(),
                "the one-off creation must persist exactly one reservation and nothing else, but the "
                        + "stored rows are: " + describeRows(rows));
        assertEquals(created.id(), rows.get(0).getId(),
                "the stored one-off reservation must be the one reported by the endpoint, but the stored "
                        + "rows are: " + describeRows(rows));
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
