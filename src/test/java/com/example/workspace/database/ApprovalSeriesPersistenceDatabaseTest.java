package com.example.workspace.database;

import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer proofs for SCN-013 (REQ-008).
 *
 * Two independent native cases: SCN-013#A1 asserts the stored status of every occurrence created for an
 * approval-required room, and SCN-013#A2 asserts the stored series identifier of those PENDING rows.
 * Both read committed rows from the real datasource through the repository; the response body is not used
 * as persistence evidence.
 */
@SpringBootTest
class ApprovalSeriesPersistenceDatabaseTest {

    @Autowired
    private ReservationService reservationService;

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

    @Test
    void everyOccurrenceOfApprovalRequiredSeriesIsStoredAsPending() throws Exception {
        // Occurrences: 2031-05-05 / 05-12 at 09:00-10:00 Europe/Berlin (CEST, +02:00).
        useWindow("2031-05-05T07:00:00Z", "2031-05-12T08:00:00Z");
        BookableResource room = persistRoom("WB013A", true);
        ReservationSeriesInput input = seriesInput(room, "WB013 approval series",
                "2031-05-05T09:00:00+02:00", "2031-05-05T10:00:00+02:00", 2);

        assertDoesNotThrow(() -> reservationService.createSeries(input, "alice"),
                "series creation for an active approval-required room must succeed");

        List<Reservation> stored = storedInWindow();
        assertEquals(2, stored.size(),
                "both requested occurrences must be stored for the approval-required room; stored rows: "
                        + describe(stored));
        assertTrue(stored.stream().allMatch(row -> row.getStatus() == ReservationStatus.PENDING),
                "every stored occurrence of the approval-required series must be PENDING; stored rows: "
                        + describe(stored));
    }

    @Test
    void pendingOccurrencesOfOneSeriesShareOneNonNullSeriesId() throws Exception {
        useWindow("2031-05-05T07:00:00Z", "2031-05-12T08:00:00Z");
        BookableResource room = persistRoom("WB013B", true);
        ReservationSeriesInput input = seriesInput(room, "WB013 approval series",
                "2031-05-05T09:00:00+02:00", "2031-05-05T10:00:00+02:00", 2);

        assertDoesNotThrow(() -> reservationService.createSeries(input, "alice"),
                "series creation for an active approval-required room must succeed");

        List<Reservation> stored = storedInWindow();
        assertEquals(2, stored.size(),
                "both requested occurrences must be stored before the seriesId can be checked; stored rows: "
                        + describe(stored));

        List<Reservation> pending = stored.stream()
                .filter(row -> row.getStatus() == ReservationStatus.PENDING)
                .collect(Collectors.toList());
        assertEquals(2, pending.size(),
                "both stored occurrences must be PENDING for the seriesId assertion to apply; stored rows: "
                        + describe(stored));

        List<UUID> seriesIds = pending.stream().map(Reservation::getSeriesId).collect(Collectors.toList());
        assertTrue(seriesIds.stream().noneMatch(Objects::isNull),
                "every PENDING occurrence must carry a non-null seriesId, but got " + seriesIds);
        assertEquals(1, new HashSet<>(seriesIds).size(),
                "all PENDING occurrences of one series must share the same seriesId, but got " + seriesIds);
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

    private BookableResource persistRoom(String codePrefix, boolean approvalRequired) {
        String code = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        BookableResource saved = resourceRepository.save(new BookableResource(
                code, "Database fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, approvalRequired));
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

    private static String describe(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " status=" + row.getStatus() + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
