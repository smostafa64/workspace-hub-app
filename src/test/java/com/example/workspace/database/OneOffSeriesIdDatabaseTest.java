package com.example.workspace.database;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Database-layer proof for SCN-016#A1 (REQ-007, REQ-012, SE-002).
 *
 * The pre-existing one-off creation path is driven through the real service that backs the existing
 * endpoint, against the configured datasource. The case then independently reads the committed row.
 *
 * The frozen assertion has two clauses and both are observed here: the created reservation reports a
 * null seriesId in the value returned by the creation call, and the durable row carries a null series
 * identifier. A second, separate native case is not needed because these are one atomic creation
 * outcome; a mapper that nulls the response field or a column populated with a generated identifier
 * would each fail this case at its own assertion. No approval outcome or other pre-existing booking rule
 * is asserted, because that content is not frozen (AMB-010).
 */
@SpringBootTest
class OneOffSeriesIdDatabaseTest {

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
    void oneOffCreationReportsNullSeriesIdAndPersistsNullSeriesIdentifier() {
        useWindow("2031-08-04T06:00:00Z", "2031-08-04T09:00:00Z");
        BookableResource room = persistRoom("WB016A");

        Instant start = Instant.parse("2031-08-04T07:00:00Z");
        Instant end = Instant.parse("2031-08-04T08:00:00Z");

        ReservationView created = reservationService.create(
                new BookingInput(room.getId(), "WB016 one-off reservation", start, end), "alice");

        assertNotNull(created, "the existing one-off creation path must return the created reservation");
        assertNotNull(created.id(), "the created reservation must be reported with an identifier");
        assertNull(created.seriesId(),
                "a one-off reservation must report seriesId as null in the creation response");

        List<Reservation> stored = storedInWindow();
        assertEquals(1, stored.size(),
                "the one-off creation must persist exactly one reservation and nothing else; stored rows: "
                        + describeRows(stored));

        Reservation row = stored.get(0);
        assertEquals(created.id(), row.getId(),
                "the response must identify the reservation that was actually stored");
        assertEquals(start, row.getStartsAt(),
                "the stored one-off reservation must keep the requested start instant");
        assertEquals(end, row.getEndsAt(),
                "the stored one-off reservation must keep the requested end instant");
        assertNull(row.getSeriesId(),
                "the one-off reservation must be stored with a null series identifier, but the stored "
                        + "seriesId was " + row.getSeriesId() + "; stored rows: " + describeRows(stored));
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

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
