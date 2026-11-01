package com.example.workspace.service;

import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Unit-layer proofs for SCN-001#A2, SCN-002#A1 and SCN-003#A1 of the frozen reservation-series
 * contract (REQ-001, REQ-002, REQ-003).
 *
 * <p>Boundary: no Spring context, no HTTP, no datasource. The application service
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} is driven directly with
 * mocked repositories, so what executes is the in-process occurrence computation/collation and the
 * occurrences range handling. The observation is the service's own output (the returned reservation
 * series views) plus the reservation entities it hands to the repository port for persistence.
 *
 * <p>SCN-001#A2 is exercised with a three-week window that straddles the Europe/Berlin offset change
 * (2031-10-20 09:00 +02:00, 2031-10-27 09:00 +01:00, 2031-11-03 09:00 +01:00). A wall-clock
 * implementation keeps 09:00 local for every occurrence while the UTC instants change; naive UTC
 * arithmetic (start instant plus 7*24h) would drift to 08:00 local after the transition and fail the
 * assertions. The chosen local time (09:00) is never in a DST gap or overlap, so the unresolved
 * gap/overlap policy (AMB-001) is not touched.
 *
 * <p>SCN-002#A1 and SCN-003#A1 use occurrences 2 and 12, the documented inclusive bounds (REQ-002),
 * with one-hour durations (strictly inside the 15-minute/8-hour window, so AMB-002 is not touched) and
 * future start instants relative to the injected fixed clock. Each test counts both the returned
 * reservation views and the entities actually offered to the repository, so a response that merely
 * echoes the requested count cannot satisfy the count assertion.
 */
class ReservationSeriesUnitTest {

    private static final ZoneId REQUESTED_ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalTime REQUESTED_LOCAL_START = LocalTime.of(9, 0);
    private static final long RESOURCE_ID = 1L;

    /** Frozen, future reference instant so every occurrence passes the future-date rule. */
    private final Clock clock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** Mirrors the production Jackson binding used for the documented JSON payload (REQ-001). */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Reservations the service offered to the persistence port, recorded by the mock's answer. */
    private final List<Reservation> persistedReservations = new ArrayList<>();

    private ReservationService service;
    private BookableResource room;

    @BeforeEach
    void setUp() {
        persistedReservations.clear();
        room = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM, "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(room, "id", RESOURCE_ID);
        service = new ReservationService(resourceRepository(), reservationRepository(),
                mock(AuditRepository.class), clock);
    }

    /**
     * SCN-001#A2: each of the three weekly reservations starts at the same local clock time (09:00) in
     * the requested Europe/Berlin zone, including the occurrence on the other side of the DST change.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-001-weekly-3-spanning-dst-change")
    void sameLocalStartTimeAcrossWeeklyOccurrencesAcrossDst() throws Exception {
        ReservationSeriesView series = createSeries(
                "2031-10-20T09:00:00+02:00", "2031-10-20T10:00:00+02:00", 3);

        List<ReservationView> occurrences = series.reservations();
        assertNotNull(occurrences,
                "the accepted three-occurrence series must return its reservation views, but the "
                        + "returned collection was null");
        assertEquals(3, occurrences.size(),
                "the three-occurrence series must expose all three reservations so their local start "
                        + "times can be compared, but the returned collection held " + occurrences.size()
                        + " entries");

        Set<LocalTime> observedLocalStarts = new LinkedHashSet<>();
        for (ReservationView occurrence : occurrences) {
            assertNotNull(occurrence, "the returned reservation collection must not contain null entries");
            assertNotNull(occurrence.startsAt(),
                    "reservation '" + occurrence.title() + "' has no start instant");
            LocalTime localStart = occurrence.startsAt().atZone(REQUESTED_ZONE).toLocalTime();
            observedLocalStarts.add(localStart);
            assertEquals(REQUESTED_LOCAL_START, localStart,
                    "every occurrence must start at " + REQUESTED_LOCAL_START + " local time in "
                            + REQUESTED_ZONE + ", but a reservation started at " + localStart
                            + " local (start instant " + occurrence.startsAt() + ")");
        }

        assertEquals(Set.of(REQUESTED_LOCAL_START), observedLocalStarts,
                "all occurrences of the weekly series must share the same local start time in "
                        + REQUESTED_ZONE + ", but the observed local start times were "
                        + observedLocalStarts);
    }

    /**
     * SCN-002#A1: the lower bound occurrences=2 is accepted (no validation exception) and the service
     * creates exactly two reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-002-occurrences-lower-bound-2")
    void occurrencesTwoIsAcceptedAndExpandsToTwoReservations() throws Exception {
        ReservationSeriesView series = assertDoesNotThrow(
                () -> createSeries("2031-02-03T09:00:00+01:00", "2031-02-03T10:00:00+01:00", 2),
                "occurrences=2 is the documented lower bound of the 2-12 range and must be accepted");

        assertNotNull(series.reservations(),
                "the accepted two-occurrence series must return its reservation views, but the "
                        + "returned collection was null");
        assertEquals(2, series.reservations().size(),
                "the accepted two-occurrence series must return exactly 2 reservations, but returned "
                        + series.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(2, persistedReservations.size(),
                "the accepted two-occurrence series must create exactly 2 reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * SCN-003#A1: the upper bound occurrences=12 is accepted (no validation exception) and the service
     * creates exactly twelve reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-003-occurrences-upper-bound-12")
    void occurrencesTwelveIsAcceptedAndExpandsToTwelveReservations() throws Exception {
        ReservationSeriesView series = assertDoesNotThrow(
                () -> createSeries("2031-01-06T09:00:00+01:00", "2031-01-06T10:00:00+01:00", 12),
                "occurrences=12 is the documented upper bound of the 2-12 range and must be accepted");

        assertNotNull(series.reservations(),
                "the accepted twelve-occurrence series must return its reservation views, but the "
                        + "returned collection was null");
        assertEquals(12, series.reservations().size(),
                "the accepted twelve-occurrence series must return exactly 12 reservations, but "
                        + "returned " + series.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(12, persistedReservations.size(),
                "the accepted twelve-occurrence series must create exactly 12 reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * Builds the documented series payload through the same JSON binding the API uses (REQ-001), so the
     * service receives an instance whose fields are populated exactly as they are on the wire.
     */
    private ReservationSeriesView createSeries(String startsAt, String endsAt, int occurrences)
            throws Exception {
        String json = "{\"resourceId\":" + RESOURCE_ID
                + ",\"title\":\"Weekly planning\""
                + ",\"startsAt\":\"" + startsAt + "\""
                + ",\"endsAt\":\"" + endsAt + "\""
                + ",\"timezone\":\"" + REQUESTED_ZONE.getId() + "\""
                + ",\"frequency\":\"WEEKLY\""
                + ",\"occurrences\":" + occurrences + "}";
        ReservationSeriesInput input = objectMapper.readValue(json, ReservationSeriesInput.class);
        return service.createSeries(input, "alice");
    }

    /**
     * Resource port: every Optional-returning lookup resolves to the active meeting room, so the
     * scenario precondition (active room without approval requirement) holds. Other query shapes fall
     * back to Mockito's empty defaults.
     */
    private ResourceRepository resourceRepository() {
        return mock(ResourceRepository.class, invocation -> {
            if (Optional.class.equals(invocation.getMethod().getReturnType())) {
                return Optional.of(room);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /**
     * Reservation port: records every reservation the service persists (single or batch save) and
     * reports no conflicting reservations, keeping the recurring window conflict-free. Conflict
     * queries fall back to Mockito's empty/false defaults, so no pre-existing booking is visible.
     */
    private ReservationRepository reservationRepository() {
        return mock(ReservationRepository.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();
            if (methodName.startsWith("save") && arguments.length == 1) {
                if (arguments[0] instanceof Reservation) {
                    Reservation single = (Reservation) arguments[0];
                    persistedReservations.add(single);
                    return single;
                }
                if (arguments[0] instanceof Iterable) {
                    List<Reservation> batch = new ArrayList<>();
                    for (Object element : (Iterable<?>) arguments[0]) {
                        if (element instanceof Reservation) {
                            Reservation reservation = (Reservation) element;
                            persistedReservations.add(reservation);
                            batch.add(reservation);
                        }
                    }
                    return batch;
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private String describeStarts() {
        List<String> starts = new ArrayList<>();
        for (Reservation reservation : persistedReservations) {
            starts.add(String.valueOf(reservation.getStartsAt()));
        }
        return starts.toString();
    }
}
