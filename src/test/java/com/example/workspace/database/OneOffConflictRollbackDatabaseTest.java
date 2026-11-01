package com.example.workspace.database;

import com.example.workspace.api.ApiException;
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
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer proof for SCN-008#A2 (REQ-005, REQ-006).
 *
 * A 2-occurrence weekly series is submitted while a pre-existing one-off reservation overlaps only the
 * last occurrence. The assertion is about durable state: after the rejection only the pre-existing one-off
 * row may remain. This is observed by reading committed rows from the real datasource, not from the
 * rejection response.
 */
@SpringBootTest
class OneOffConflictRollbackDatabaseTest {

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
    void overlapOnLastOccurrenceWithExistingOneOffPersistsOnlyTheOneOff() throws Exception {
        // Occurrences: 2031-04-07 / 04-14 at 09:00-10:00 Europe/Berlin (CEST, +02:00 in April).
        useWindow("2031-04-07T07:00:00Z", "2031-04-14T08:00:00Z");
        BookableResource room = persistRoom("WB008A", false);
        Reservation oneOff = persistReservation(room, "2031-04-14T07:30:00Z", "2031-04-14T08:30:00Z",
                ReservationStatus.CONFIRMED, null);

        ReservationSeriesInput input = seriesInput(room, "WB008 weekly series",
                "2031-04-07T09:00:00+02:00", "2031-04-07T10:00:00+02:00", 2);

        Throwable failure = assertThrows(Throwable.class,
                () -> reservationService.createSeries(input, "alice"),
                "the series must be rejected while its last occurrence overlaps the existing one-off reservation");
        assertTrue(isConflictRejection(failure),
                "expected the frozen 409 conflict rejection, but the failure was: " + failure);

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "only the pre-existing one-off reservation may remain stored; stored rows: " + describe(stored));
        assertEquals(oneOff.getId(), stored.get(0).getId(),
                "the pre-existing one-off reservation must be the only surviving row");

        Instant firstOccurrenceStart = Instant.parse("2031-04-07T07:00:00Z");
        assertTrue(stored.stream().noneMatch(row -> firstOccurrenceStart.equals(row.getStartsAt())),
                "the first (non-conflicting) occurrence was persisted despite the rejected series");
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

    private static boolean isConflictRejection(Throwable failure) {
        if (failure instanceof ApiException apiException) {
            return HttpStatus.CONFLICT.equals(apiException.status());
        }
        return "ReservationConflictException".equals(failure.getClass().getSimpleName());
    }

    private static String describe(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
