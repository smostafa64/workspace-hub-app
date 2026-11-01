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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer proofs for SCN-017#A1 and SCN-017#A2 (REQ-007, REQ-013, SE-002).
 *
 * The three cases use different, future fixture months and unique resources, so they cannot observe each
 * other's rows. Each case creates its data through the real service and then reads committed rows from
 * the configured datasource through the repository; no response body is used as storage evidence.
 *
 * A1 is split into two independently falsifiable cases because the frozen assertion states two distinct
 * durable facts: (a) the identifier stored for every occurrence is the same non-null value, and (b) the
 * stored value reads back identically for each occurrence. A corrupted write and a corrupted read path
 * are different faults, and a single case would let one hide the other.
 *
 * A2 only asserts the frozen storage/readability outcome. It deliberately does not assert a migration
 * mechanism: the pre-existing one-off row is materialised in the state the scenario starts from
 * (null series identifier) before a new series is created against the same schema, and is then read back.
 */
@SpringBootTest
class SeriesIdDurabilityDatabaseTest {

    private static final String SERIES_TITLE = "WB017 weekly series";

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

    /**
     * SCN-017#A1, durable half: a 3-occurrence weekly series must leave exactly three rows and every row
     * must carry the same non-null series identifier.
     */
    @Test
    void everySeriesOccurrenceStoresTheSameNonNullSeriesIdentifier() throws Exception {
        // Occurrences: 2031-09-08 / 09-15 / 09-22 at 09:00-10:00 Europe/Berlin (CEST, +02:00) => 07:00-08:00Z.
        useWindow("2031-09-08T06:00:00Z", "2031-09-22T09:00:00Z");
        BookableResource room = persistRoom("WB017A");
        Set<Instant> expectedStarts = Set.of(
                Instant.parse("2031-09-08T07:00:00Z"),
                Instant.parse("2031-09-15T07:00:00Z"),
                Instant.parse("2031-09-22T07:00:00Z"));

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-09-08T09:00:00+02:00", "2031-09-08T10:00:00+02:00", 3);
        reservationService.createSeries(input, "alice");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the three requested occurrences must all be stored for the series; stored rows: "
                        + describeRows(stored));
        assertEquals(expectedStarts, startsOf(stored),
                "the stored rows must be exactly the three series occurrences; stored rows: "
                        + describeRows(stored));

        List<UUID> seriesIds = stored.stream().map(Reservation::getSeriesId).collect(Collectors.toList());
        assertTrue(seriesIds.stream().noneMatch(Objects::isNull),
                "every stored occurrence of the series must carry a non-null series identifier, but got "
                        + seriesIds + "; stored rows: " + describeRows(stored));
        assertEquals(1, new HashSet<>(seriesIds).size(),
                "all occurrences of one series must share the same series identifier, but got "
                        + seriesIds + "; stored rows: " + describeRows(stored));
    }

    /**
     * SCN-017#A1, read-back half: the identifier stored for each occurrence must be readable back as the
     * same value for that occurrence. Each occurrence is re-read in its own repository call, so the value
     * is obtained from the datasource rather than from the object used to create the series.
     */
    @Test
    void storedSeriesIdentifierReadsBackIdenticallyForEachOccurrence() throws Exception {
        // Occurrences: 2031-11-03 / 11-10 / 11-17 at 09:00-10:00 Europe/Berlin (CET, +01:00) => 08:00-09:00Z.
        useWindow("2031-11-03T07:00:00Z", "2031-11-17T09:00:00Z");
        BookableResource room = persistRoom("WB017B");

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-11-03T09:00:00+01:00", "2031-11-03T10:00:00+01:00", 3);
        reservationService.createSeries(input, "alice");

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the three requested occurrences must all be stored before reading the identifier back; "
                        + "stored rows: " + describeRows(stored));

        UUID durableSeriesId = stored.get(0).getSeriesId();
        assertNotNull(durableSeriesId,
                "the first stored occurrence must carry a non-null series identifier; stored rows: "
                        + describeRows(stored));

        for (Reservation occurrence : stored) {
            Reservation reread = reservationRepository.findById(occurrence.getId())
                    .orElseThrow(() -> new AssertionError("occurrence " + occurrence.getId()
                            + " disappeared from the database before it could be read back"));
            assertNotNull(reread.getSeriesId(),
                    "occurrence starting at " + occurrence.getStartsAt()
                            + " must read back a non-null series identifier");
            assertEquals(durableSeriesId, reread.getSeriesId(),
                    "occurrence starting at " + occurrence.getStartsAt()
                            + " must read back the series identifier stored for the series, but got "
                            + reread.getSeriesId());
            assertEquals(occurrence.getStartsAt(), reread.getStartsAt(),
                    "the read-back occurrence must be the same stored booking");
        }
    }

    /**
     * SCN-017#A2: a pre-existing one-off reservation stays readable and reports a null series identifier
     * while a new series is created against the same schema.
     */
    @Test
    void preexistingOneOffReservationStaysReadableWithNullSeriesIdentifier() throws Exception {
        // Legacy one-off: 2031-12-01 09:00-10:00 Europe/Berlin (CET, +01:00) => 08:00-09:00Z.
        useWindow("2031-12-01T07:00:00Z", "2031-12-22T10:00:00Z");
        BookableResource room = persistRoom("WB017C");
        Reservation legacy = persistReservation(room, "2031-12-01T08:00:00Z", "2031-12-01T09:00:00Z",
                ReservationStatus.CONFIRMED, null);

        // The schema that carries the series identifier is exercised while the pre-existing row exists.
        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-12-08T09:00:00+01:00", "2031-12-08T10:00:00+01:00", 3);
        reservationService.createSeries(input, "alice");

        List<Reservation> stored = storedInWindow();
        assertEquals(4, stored.size(),
                "the pre-existing one-off row must coexist with the three new series rows after the new "
                        + "series is stored; stored rows: " + describeRows(stored));
        assertEquals(3, stored.stream().filter(row -> !legacy.getId().equals(row.getId())).count(),
                "the new series must have stored exactly its three occurrences next to the legacy row; "
                        + "stored rows: " + describeRows(stored));

        Reservation reread = reservationRepository.findById(legacy.getId())
                .orElseThrow(() -> new AssertionError("the pre-existing one-off reservation must remain "
                        + "readable, but it could not be read back"));

        assertNull(reread.getSeriesId(),
                "the pre-existing one-off reservation must report a null series identifier, but got "
                        + reread.getSeriesId());
        assertEquals(legacy.getStartsAt(), reread.getStartsAt(),
                "the pre-existing one-off reservation must remain readable with its stored start instant");
        assertEquals(legacy.getEndsAt(), reread.getEndsAt(),
                "the pre-existing one-off reservation must remain readable with its stored end instant");
        assertEquals(legacy.getTitle(), reread.getTitle(),
                "the pre-existing one-off reservation must remain readable with its stored title");
        assertEquals(legacy.getBookedBy(), reread.getBookedBy(),
                "the pre-existing one-off reservation must remain readable with its stored owner");
        assertEquals(legacy.getStatus(), reread.getStatus(),
                "the pre-existing one-off reservation must remain readable with its stored status");
    }

    private static Set<Instant> startsOf(List<Reservation> rows) {
        return rows.stream().map(Reservation::getStartsAt).collect(Collectors.toSet());
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

    private Reservation persistReservation(BookableResource room, String startsAt, String endsAt,
                                           ReservationStatus status, UUID seriesId) {
        Reservation reservation = new Reservation(room, "alice", "WB017 pre-existing one-off",
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

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " status=" + row.getStatus()
                        + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
