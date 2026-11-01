package com.example.workspace.integration;

import com.example.workspace.api.ReservationController;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer proof for SCN-019#A1 (REQ-003, SE-007).
 *
 * A weekly series is submitted through the real {@link ReservationController} bean of the running
 * application context with a real {@link Authentication}; the request expands through the real service
 * and the produced occurrences are committed to the configured datasource. The stored start instants are
 * then read back through the repository and interpreted in the supplied timezone, so the frozen outcome
 * is observed at the storage boundary rather than from a mocked expansion cache.
 *
 * The supplied zone is Europe/Berlin and the series window strictly contains the spring transition, so
 * before/after occurrences have different UTC offsets. A1 states the local wall-clock start must be kept
 * across that transition. It is split into two native cases with different fault models: the local-clock
 * case catches expansion by a fixed 24-hour step or a zone-shifting read path, while the raw-instant case
 * pins the distinct UTC instants the transition must produce. Neither case asserts any gap/overlap policy
 * for ambiguous or nonexistent local times; that policy is not frozen (AMB-001), and the chosen 09:00
 * local start is unambiguous on both sides of the transition.
 */
@SpringBootTest
class DstSeriesIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TITLE = "WB019 dst integration series";

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
     * SCN-019#A1, wall-clock half: every occurrence of a weekly series crossing the spring transition
     * starts at the requested 09:00 local time in Europe/Berlin.
     */
    @Test
    void everyOccurrenceStartsAtRequestedLocalClockTimeAcrossDstTransition() throws Exception {
        // Europe/Berlin DST starts 2034-03-26 02:00 CET. Occurrences: 2034-03-19 (CET, +01:00 =>
        // 08:00Z), 2034-03-26 (CEST, +02:00 => 07:00Z), 2034-04-02 (CEST => 07:00Z), all local 09:00.
        useWindow("2034-03-19T06:00:00Z", "2034-04-02T09:00:00Z");
        BookableResource room = persistRoom("WB019I");

        assertNotEquals(
                LocalDateTime.of(2034, 3, 19, 9, 0).atZone(BERLIN).getOffset(),
                LocalDateTime.of(2034, 4, 2, 9, 0).atZone(BERLIN).getOffset(),
                "precondition: the supplied zone must observe a daylight-saving transition inside the "
                        + "series window");

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2034-03-19T09:00:00+01:00", "2034-03-19T10:00:00+01:00", 3);
        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the weekly series must create its three requested occurrences; stored rows: "
                        + describeRows(stored));

        List<Reservation> ordered = stored.stream()
                .sorted(Comparator.comparing(Reservation::getStartsAt))
                .collect(Collectors.toList());
        List<LocalDate> expectedDates = List.of(
                LocalDate.of(2034, 3, 19),
                LocalDate.of(2034, 3, 26),
                LocalDate.of(2034, 4, 2));

        List<Executable> checks = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Reservation occurrence = ordered.get(index);
            int expectedIndex = index;
            checks.add(() -> {
                ZonedDateTime localStart = occurrence.getStartsAt().atZone(BERLIN);
                assertEquals(expectedDates.get(expectedIndex), localStart.toLocalDate(),
                        "occurrence " + expectedIndex + " must fall on the requested weekly date in "
                                + "the supplied timezone, but the stored row is: " + describeRow(occurrence));
                assertEquals(LocalTime.of(9, 0), localStart.toLocalTime(),
                        "occurrence " + expectedIndex + " must start at the requested 09:00 local time "
                                + "in Europe/Berlin across the transition, but the stored row is: "
                                + describeRow(occurrence));
                assertEquals(LocalTime.of(10, 0), occurrence.getEndsAt().atZone(BERLIN).toLocalTime(),
                        "occurrence " + expectedIndex + " must end at the requested 10:00 local time in "
                                + "Europe/Berlin across the transition, but the stored row is: "
                                + describeRow(occurrence));
            });
        }
        assertAll(checks);
    }

    /**
     * SCN-019#A1, instant half: the occurrences on either side of the transition are stored at the
     * correct distinct UTC instants.
     */
    @Test
    void occurrencesAcrossDstTransitionHaveTheExpectedDistinctUtcInstants() throws Exception {
        // Europe/Berlin DST starts 2035-03-25 02:00 CET. Occurrences: 2035-03-18 (CET => 08:00Z),
        // 2035-03-25 (CEST => 07:00Z), 2035-04-01 (CEST => 07:00Z).
        useWindow("2035-03-18T06:00:00Z", "2035-04-01T09:00:00Z");
        BookableResource room = persistRoom("WB019J");

        ReservationSeriesInput input = seriesInput(room, SERIES_TITLE,
                "2035-03-18T09:00:00+01:00", "2035-03-18T10:00:00+01:00", 3);
        submitSeries(input);

        List<Reservation> stored = storedInWindow();
        assertEquals(3, stored.size(),
                "the weekly series must create its three requested occurrences; stored rows: "
                        + describeRows(stored));

        Set<Instant> expectedStarts = Set.of(
                Instant.parse("2035-03-18T08:00:00Z"),
                Instant.parse("2035-03-25T07:00:00Z"),
                Instant.parse("2035-04-01T07:00:00Z"));
        Set<Instant> actualStarts = stored.stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toSet());
        assertEquals(expectedStarts, actualStarts,
                "the occurrences must be stored at the UTC instants produced by keeping 09:00 local "
                        + "time across the offset change; stored rows: " + describeRows(stored));
        assertEquals(3, actualStarts.size(),
                "the three occurrences must be distinct instants; stored rows: " + describeRows(stored));

        List<Reservation> ordered = stored.stream()
                .sorted(Comparator.comparing(Reservation::getStartsAt))
                .collect(Collectors.toList());
        assertEquals(ZoneOffset.ofHours(1), ordered.get(0).getStartsAt().atZone(BERLIN).getOffset(),
                "the first occurrence must be stored before the offset change (CET, +01:00); stored "
                        + "rows: " + describeRows(stored));
        assertEquals(ZoneOffset.ofHours(2), ordered.get(1).getStartsAt().atZone(BERLIN).getOffset(),
                "the second occurrence must be stored after the offset change (CEST, +02:00); stored "
                        + "rows: " + describeRows(stored));
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

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String describeRow(Reservation row) {
        return "id=" + row.getId() + " startsAt=" + row.getStartsAt()
                + " endsAt=" + row.getEndsAt() + " seriesId=" + row.getSeriesId();
    }

    private static String describeRows(List<Reservation> rows) {
        return rows.stream()
                .map(DstSeriesIntegrationTest::describeRow)
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
