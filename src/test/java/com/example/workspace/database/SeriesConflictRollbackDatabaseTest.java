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
 * Database-layer proof for SCN-007#A2 (REQ-005, REQ-006).
 *
 * The test drives the real transactional series creation against the configured datasource and then
 * independently reads the committed rows through the repository. No HTTP response is used as evidence
 * for persistence: the frozen assertion is that the rejected 3-occurrence series leaves no row behind,
 * including the first and third occurrences that do not overlap anything.
 */
@SpringBootTest
class SeriesConflictRollbackDatabaseTest {

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
    void conflictOnSecondOccurrenceLeavesNoSeriesRows() throws Exception {
        // Occurrences: 2031-03-03 / 03-10 / 03-17 at 09:00-10:00 Europe/Berlin (CET, +01:00 in March).
        useWindow("2031-03-03T08:00:00Z", "2031-03-17T09:00:00Z");
        BookableResource room = persistRoom("WB007A", false);
        Reservation oneOff = persistReservation(room, "2031-03-10T08:30:00Z", "2031-03-10T09:30:00Z",
                ReservationStatus.CONFIRMED, null);

        ReservationSeriesInput input = seriesInput(room, "WB007 weekly series",
                "2031-03-03T09:00:00+01:00", "2031-03-03T10:00:00+01:00", 3);

        Throwable failure = assertThrows(Throwable.class,
                () -> reservationService.createSeries(input, "alice"),
                "the series must be rejected while its second occurrence overlaps the existing reservation");
        assertTrue(isConflictRejection(failure),
                "expected the frozen 409 conflict rejection, but the failure was: " + failure);

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "no occurrence of the rejected series may be persisted; stored rows: " + describe(stored));
        assertEquals(oneOff.getId(), stored.get(0).getId(),
                "the pre-existing one-off reservation must be the only surviving row");

        Instant firstOccurrenceStart = Instant.parse("2031-03-03T08:00:00Z");
        Instant thirdOccurrenceStart = Instant.parse("2031-03-17T08:00:00Z");
        assertTrue(stored.stream().noneMatch(r -> firstOccurrenceStart.equals(r.getStartsAt())),
                "the first (non-conflicting) occurrence was persisted despite the rejected series");
        assertTrue(stored.stream().noneMatch(r -> thirdOccurrenceStart.equals(r.getStartsAt())),
                "the third (non-conflicting) occurrence was persisted despite the rejected series");
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

    private static boolean isConflictRejection(Throwable failure) {
        if (failure instanceof ApiException apiException) {
            return HttpStatus.CONFLICT.equals(apiException.status());
        }
        // The conflict carrier exposed by the technical signatures; checked by name because the
        // conflict exception is the only documented 409 signal besides ApiException(CONFLICT).
        return "ReservationConflictException".equals(failure.getClass().getSimpleName());
    }

    private static String describe(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
