package com.example.workspace.integration;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer proofs for SCN-013#A1 and SCN-013#A2 (REQ-008).
 *
 * The frozen scenario rationale requires the cross-boundary confirmation of the approval side effect:
 * the real {@link ReservationController} bean of the running application context is invoked with a real
 * {@link Authentication}, the request runs through the real transactional service, and the committed
 * occurrences are read back through the repository. No service or repository is mocked, so the PENDING
 * state and the shared series identifier are observed where they really live, not in a stub.
 *
 * A1 observes the status of every occurrence created for an active approval-required room; A2 observes
 * that the created PENDING occurrences carry one and the same non-null series identifier. Each case owns
 * its future week and resource. The success code and the response body shape are not asserted because
 * they are not frozen (AMB-009); only a 2xx success guard is used, on top of the durable rows.
 */
@SpringBootTest
class ApprovalSeriesIntegrationTest {

    private static final Instant SERIES_A_FIRST = Instant.parse("2032-04-05T07:00:00Z");
    private static final Instant SERIES_A_SECOND = Instant.parse("2032-04-12T07:00:00Z");
    private static final Instant SERIES_B_FIRST = Instant.parse("2032-04-19T07:00:00Z");
    private static final Instant SERIES_B_SECOND = Instant.parse("2032-04-26T07:00:00Z");

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
    void everyOccurrenceCreatedForApprovalRequiredRoomIsPending() throws Exception {
        // Occurrences: 2032-04-05 / 04-12 at 09:00-10:00 Europe/Berlin (CEST, +02:00) => 07:00-08:00Z.
        useWindow("2032-04-05T06:00:00Z", "2032-04-12T09:00:00Z");
        BookableResource room = persistRoom("WB013I", true);
        ReservationSeriesInput input = seriesInput(room, "WB013 integration series",
                "2032-04-05T09:00:00+02:00", "2032-04-05T10:00:00+02:00", 2);

        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(2, stored.size(),
                "the approval-required series must create exactly its two requested occurrences, but the "
                        + "stored rows are: " + describe(stored));
        assertEquals(Set.of(SERIES_A_FIRST, SERIES_A_SECOND), startsOf(stored),
                "the created occurrences must be the two requested weekly instants; stored rows: "
                        + describe(stored));

        List<ReservationStatus> statuses = stored.stream()
                .map(Reservation::getStatus)
                .collect(Collectors.toList());
        assertTrue(statuses.stream().allMatch(status -> status == ReservationStatus.PENDING),
                "every occurrence created for the approval-required room must be PENDING, but the stored "
                        + "statuses were " + statuses + "; stored rows: " + describe(stored));
    }

    @Test
    void pendingOccurrencesOfOneApprovalSeriesShareOneNonNullSeriesId() throws Exception {
        // Occurrences: 2032-04-19 / 04-26 at 09:00-10:00 Europe/Berlin (CEST, +02:00) => 07:00-08:00Z.
        useWindow("2032-04-19T06:00:00Z", "2032-04-26T09:00:00Z");
        BookableResource room = persistRoom("WB013J", true);
        ReservationSeriesInput input = seriesInput(room, "WB013 integration series",
                "2032-04-19T09:00:00+02:00", "2032-04-19T10:00:00+02:00", 2);

        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(2, stored.size(),
                "both requested occurrences must be stored before their series identifiers are compared, "
                        + "but the stored rows are: " + describe(stored));
        assertEquals(Set.of(SERIES_B_FIRST, SERIES_B_SECOND), startsOf(stored),
                "the stored rows must be the two requested weekly instants; stored rows: " + describe(stored));

        List<Reservation> pending = stored.stream()
                .filter(row -> row.getStatus() == ReservationStatus.PENDING)
                .collect(Collectors.toList());
        assertEquals(2, pending.size(),
                "both created occurrences must be PENDING for the series identifier assertion to apply, "
                        + "but the stored rows are: " + describe(stored));

        List<UUID> seriesIds = pending.stream().map(Reservation::getSeriesId).collect(Collectors.toList());
        assertTrue(seriesIds.stream().noneMatch(Objects::isNull),
                "every PENDING occurrence created for the series must carry a non-null seriesId, but got "
                        + seriesIds + "; stored rows: " + describe(stored));
        assertEquals(1, new HashSet<>(seriesIds).size(),
                "all PENDING occurrences of one series must share the same seriesId, but got " + seriesIds
                        + "; stored rows: " + describe(stored));
    }

    private ResponseEntity<?> submitSeries(ReservationSeriesInput input) {
        ResponseEntity<?> response = reservationController.createSeries(input, alice());
        assertNotNull(response, "the series creation must return a response");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "the conflict-free series for an active approval-required room must be accepted (the exact "
                        + "success code is not frozen, AMB-009), but the response status was "
                        + response.getStatusCode());
        return response;
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
