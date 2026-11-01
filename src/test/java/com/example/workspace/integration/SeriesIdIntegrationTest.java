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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer proofs for SCN-017#A1 and SCN-017#A2 (REQ-007, REQ-013, SE-002).
 *
 * The series is created through the real {@link ReservationController} bean of the running application
 * context with a real {@link Authentication}, so the request crosses the real controller/service
 * boundary and commits through the configured datasource. The durable row set is then read independently
 * through the repository, which executes real SQL against that datasource; no response body is used as
 * storage evidence and no repository or service is mocked.
 *
 * A1 states two separately falsifiable durable facts and therefore has two native cases: (a) the value
 * stored for every occurrence is the same non-null identifier, and (b) the stored identifier reads back
 * identically for each occurrence (a corrupted write and a corrupted read mapping are different faults).
 *
 * A2 asserts only the frozen storage/readability outcome: a pre-existing one-off row is materialised in
 * the state the scenario starts from (no series identifier), a new series is then stored against the same
 * schema, and the pre-existing row is read back unchanged with a null series identifier. No migration
 * mechanism is asserted, because none is frozen.
 */
@SpringBootTest
class SeriesIdIntegrationTest {

    private static final String SERIES_TITLE = "WB017 integration series";

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

    /**
     * SCN-017#A1, write half: every stored occurrence of a 3-occurrence series shares one non-null
     * series identifier.
     */
    @Test
    void everySeriesOccurrenceStoresTheSameNonNullSeriesIdentifier() throws Exception {
        // Occurrences: 2031-06-09 / 06-16 / 06-23 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2031-06-09T06:00:00Z", "2031-06-23T09:00:00Z");
        BookableResource room = persistRoom("WB017I");
        Set<Instant> expectedStarts = Set.of(
                Instant.parse("2031-06-09T07:00:00Z"),
                Instant.parse("2031-06-16T07:00:00Z"),
                Instant.parse("2031-06-23T07:00:00Z"));

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-06-09T09:00:00+02:00", "2031-06-09T10:00:00+02:00", 3);
        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the three requested occurrences must all be stored for the series; stored rows: "
                        + describeRows(stored));
        assertEquals(expectedStarts, startsOf(stored),
                "the stored rows must be exactly the three requested weekly instants; stored rows: "
                        + describeRows(stored));

        List<UUID> seriesIds = stored.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toList());
        assertTrue(seriesIds.stream().noneMatch(Objects::isNull),
                "every stored occurrence of the series must carry a non-null series identifier, but got "
                        + seriesIds + "; stored rows: " + describeRows(stored));
        assertEquals(1, new HashSet<>(seriesIds).size(),
                "every stored occurrence of one series must share the same series identifier, but got "
                        + seriesIds + "; stored rows: " + describeRows(stored));
    }

    /**
     * SCN-017#A1, read-back half: each occurrence re-read from the datasource reports the identifier
     * stored for the series.
     */
    @Test
    void storedSeriesIdentifierReadsBackIdenticallyForEveryOccurrence() throws Exception {
        // Occurrences: 2031-05-19 / 05-26 / 06-02 at 09:00-10:00 Europe/Berlin (CEST, +02:00)
        // => 07:00-08:00Z.
        useWindow("2031-05-19T06:00:00Z", "2031-06-02T09:00:00Z");
        BookableResource room = persistRoom("WB017J");

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-05-19T09:00:00+02:00", "2031-05-19T10:00:00+02:00", 3);
        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the three requested occurrences must all be stored before the identifier is read back; "
                        + "stored rows: " + describeRows(stored));

        UUID durableSeriesId = stored.get(0).getSeriesId();
        assertNotNull(durableSeriesId,
                "the first stored occurrence must carry a non-null series identifier; stored rows: "
                        + describeRows(stored));

        List<Reservation> reread = new ArrayList<>();
        for (Reservation occurrence : stored) {
            reread.add(reservationRepository.findById(occurrence.getId())
                    .orElseThrow(() -> new AssertionError("occurrence " + occurrence.getId()
                            + " disappeared from the database before it could be read back")));
        }
        assertEquals(3, reread.size(),
                "every stored occurrence must be readable back from the datasource");
        assertEquals(startsOf(stored), startsOf(reread),
                "the rows read back must be the same three stored occurrences; re-read rows: "
                        + describeRows(reread));

        List<UUID> rereadSeriesIds = reread.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toList());
        assertTrue(rereadSeriesIds.stream().noneMatch(Objects::isNull),
                "every occurrence must read back a non-null series identifier, but got "
                        + rereadSeriesIds + "; re-read rows: " + describeRows(reread));
        assertEquals(1, new HashSet<>(rereadSeriesIds).size(),
                "every occurrence must read back the same series identifier, but got "
                        + rereadSeriesIds + "; re-read rows: " + describeRows(reread));
        assertEquals(durableSeriesId, rereadSeriesIds.get(0),
                "each occurrence must read back the series identifier durably stored for the series, "
                        + "but got " + rereadSeriesIds + "; stored rows: " + describeRows(stored));
    }

    /**
     * SCN-017#A2: a pre-existing one-off reservation stays readable with a null series identifier while
     * a new series is stored next to it against the same schema.
     */
    @Test
    void preexistingOneOffReservationStaysReadableWithNullSeriesIdentifier() throws Exception {
        // Legacy one-off: 2031-08-11 09:00-10:00 Europe/Berlin (CEST, +02:00) => 07:00-08:00Z.
        useWindow("2031-08-11T06:00:00Z", "2031-09-01T09:00:00Z");
        BookableResource room = persistRoom("WB017K");
        Reservation legacy = persistReservation(room, "2031-08-11T07:00:00Z", "2031-08-11T08:00:00Z");

        // A new 3-occurrence series (2031-08-18 / 08-25 / 09-01 at 09:00 Berlin => 07:00Z) is stored
        // while the pre-existing one-off row exists.
        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2031-08-18T09:00:00+02:00", "2031-08-18T10:00:00+02:00", 3);
        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(4, stored.size(),
                "the pre-existing one-off row must coexist with the three new series rows; stored rows: "
                        + describeRows(stored));
        assertEquals(3, stored.stream().filter(row -> !legacy.getId().equals(row.getId())).count(),
                "the new series must have stored exactly its three occurrences next to the legacy row; "
                        + "stored rows: " + describeRows(stored));

        Reservation reread = reservationRepository.findById(legacy.getId())
                .orElseThrow(() -> new AssertionError("the pre-existing one-off reservation must remain "
                        + "readable, but it could not be read back"));

        assertNull(reread.getSeriesId(),
                "the pre-existing one-off reservation must report a null series identifier, but got "
                        + reread.getSeriesId() + "; stored rows: " + describeRows(stored));
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

    private void submitSeries(ReservationSeriesInput input) {
        ResponseEntity<?> response = reservationController.createSeries(input, alice());
        assertNotNull(response, "the series creation must return a response");
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "the conflict-free series must be accepted (the exact success code is not frozen, "
                        + "AMB-009), but the response status was " + response.getStatusCode());
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
                code, "Integration fixture " + code, ResourceType.ROOM, "HQ", 2, 10, true, false));
        fixtureResourceId = saved.getId();
        assertNotNull(fixtureResourceId, "the resource fixture must be persisted with an id");
        return saved;
    }

    private Reservation persistReservation(BookableResource room, String startsAt, String endsAt) {
        Reservation reservation = new Reservation(room, "alice", "WB017 pre-existing one-off",
                Instant.parse(startsAt), Instant.parse(endsAt), ReservationStatus.CONFIRMED, Instant.now());
        Reservation saved = reservationRepository.save(reservation);
        assertNotNull(saved.getId(), "the pre-existing reservation fixture must be persisted with an id");
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

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(row -> "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                        + " endsAt=" + row.getEndsAt() + " seriesId=" + row.getSeriesId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
