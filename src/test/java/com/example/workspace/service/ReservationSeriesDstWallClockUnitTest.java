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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Unit-layer proof for SCN-019#A1 of the frozen reservation-series contract (REQ-003): a weekly
 * series that straddles a real daylight-saving transition must keep the requested local clock time
 * (09:00) in the supplied zone for every occurrence, while the occurrences resolve to distinct UTC
 * instants either side of the offset change.
 *
 * <p>Boundary: no Spring context, no HTTP, no datasource, no network. The application service
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} runs in-process with mocked
 * persistence ports; the observation is the start instant of every returned occurrence, interpreted
 * in the requested zone.
 *
 * <p>Window: Europe/Berlin, weekly from 2031-10-20T09:00+02:00 (Monday, before the last-Sunday-of-
 * October change on 2031-10-26) over three occurrences, so the second and third occurrences fall
 * after the offset change (+01:00). Instant arithmetic (start instant + 7*24h) would yield 08:00
 * local for the post-transition occurrences and fail; local wall-clock expansion keeps 09:00. The
 * test also proves the window really spans the transition by requiring both offsets to appear, so it
 * cannot pass vacuously on a non-transition week.
 *
 * <p>09:00 local is never inside a DST gap or overlap for this zone, so the unresolved gap/overlap
 * policy (AMB-001, ASSUMPTION_REQUIRED) is deliberately not exercised or frozen.
 */
class ReservationSeriesDstWallClockUnitTest {

    private static final ZoneId REQUESTED_ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalTime REQUESTED_LOCAL_START = LocalTime.of(9, 0);
    private static final long RESOURCE_ID = 1L;

    /** Frozen instant before the whole series, so every occurrence passes the future-date rule. */
    private static final Instant FIXED_NOW = Instant.parse("2031-06-01T00:00:00Z");

    private static final String SERIES_START = "2031-10-20T09:00:00+02:00";
    private static final String SERIES_END = "2031-10-20T10:00:00+02:00";
    private static final int OCCURRENCES = 3;

    /** The three local dates a weekly wall-clock expansion of the 2031-10-20 start must produce. */
    private static final Set<LocalDate> EXPECTED_LOCAL_DATES = Set.of(
            LocalDate.of(2031, 10, 20),
            LocalDate.of(2031, 10, 27),
            LocalDate.of(2031, 11, 3));

    /** Berlin is on CEST (+02:00) before the change and CET (+01:00) after it. */
    private static final Set<ZoneOffset> EXPECTED_OFFSETS = Set.of(
            ZoneOffset.ofHours(2), ZoneOffset.ofHours(1));

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    /** Mirrors the production Jackson binding used for the documented JSON payload (REQ-001). */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Reservations the service offered to the persistence port, recorded by the mock's answer. */
    private final List<Reservation> persistedReservations = new ArrayList<>();

    private ReservationService service;

    @BeforeEach
    void setUp() {
        persistedReservations.clear();
        BookableResource room = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM,
                "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(room, "id", RESOURCE_ID);
        service = new ReservationService(resourceRepository(room), reservationRepository(),
                mock(AuditRepository.class), clock);
    }

    /**
     * SCN-019#A1: every occurrence of the weekly series starts at 09:00 local in Europe/Berlin across
     * the 2031 DST transition, with the offsets on either side of the change observed.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-019-weekly-series-spanning-berlin-dst-transition")
    void everyOccurrenceKeepsRequestedLocalClockTimeAcrossDstTransition() throws Exception {
        ReservationSeriesView series = service.createSeries(seriesInput(), "alice");

        List<ReservationView> occurrences = series.reservations();
        assertNotNull(occurrences,
                "the accepted three-occurrence series must return its reservation views, but the "
                        + "returned collection was null");
        assertEquals(OCCURRENCES, occurrences.size(),
                "the series must expose all " + OCCURRENCES + " occurrences so the local clock time of "
                        + "each can be compared, but the returned collection held " + occurrences.size()
                        + " entries");

        Set<LocalTime> observedLocalStarts = new LinkedHashSet<>();
        Set<LocalDate> observedLocalDates = new LinkedHashSet<>();
        Set<ZoneOffset> observedOffsets = new LinkedHashSet<>();
        Set<Instant> observedInstants = new LinkedHashSet<>();

        for (ReservationView occurrence : occurrences) {
            assertNotNull(occurrence, "the returned reservation collection must not contain null entries");
            assertNotNull(occurrence.startsAt(),
                    "reservation '" + occurrence.title() + "' has no start instant");

            ZonedDateTime localStart = occurrence.startsAt().atZone(REQUESTED_ZONE);
            observedLocalStarts.add(localStart.toLocalTime());
            observedLocalDates.add(localStart.toLocalDate());
            observedOffsets.add(localStart.getOffset());
            observedInstants.add(occurrence.startsAt());

            assertEquals(REQUESTED_LOCAL_START, localStart.toLocalTime(),
                    "every occurrence of the weekly series must start at " + REQUESTED_LOCAL_START
                            + " local time in " + REQUESTED_ZONE + ", but an occurrence started at "
                            + localStart.toLocalTime() + " local (instant " + occurrence.startsAt()
                            + ", offset " + localStart.getOffset() + ")");
        }

        assertEquals(Set.of(REQUESTED_LOCAL_START), observedLocalStarts,
                "all occurrences must share the requested local clock time " + REQUESTED_LOCAL_START
                        + " in " + REQUESTED_ZONE + ", but the observed local start times were "
                        + observedLocalStarts);

        // The window must genuinely straddle the transition, otherwise the local-time assertion above
        // could pass on a week with no offset change.
        assertEquals(EXPECTED_OFFSETS, observedOffsets,
                "the series window must span the Europe/Berlin daylight-saving transition, so the "
                        + "occurrences must be observed with both the +02:00 and the +01:00 offset, but "
                        + "the observed offsets were " + observedOffsets);

        // Wall-clock weekly expansion: same local time, seven local days apart, distinct instants.
        assertEquals(EXPECTED_LOCAL_DATES, observedLocalDates,
                "the weekly series must produce one occurrence on each of " + EXPECTED_LOCAL_DATES
                        + " local, but the observed local dates were " + observedLocalDates);
        assertEquals(OCCURRENCES, observedInstants.size(),
                "the occurrences must resolve to " + OCCURRENCES + " distinct UTC instants, but only "
                        + observedInstants.size() + " distinct instant(s) were observed: "
                        + observedInstants);

        assertEquals(OCCURRENCES, persistedReservations.size(),
                "the accepted series must create exactly " + OCCURRENCES + " reservations, but "
                        + persistedReservations.size() + " were offered to the repository");

        Set<Instant> persistedInstants = new LinkedHashSet<>();
        for (Reservation reservation : persistedReservations) {
            persistedInstants.add(reservation.getStartsAt());
        }
        assertEquals(observedInstants, persistedInstants,
                "the persisted occurrences must be the same instants reported by the series response, "
                        + "but persisted " + persistedInstants + " and returned " + observedInstants);
    }

    /** Builds the documented series payload through the same JSON binding the API uses (REQ-001). */
    private ReservationSeriesInput seriesInput() throws Exception {
        String json = "{\"resourceId\":" + RESOURCE_ID
                + ",\"title\":\"Weekly planning\""
                + ",\"startsAt\":\"" + SERIES_START + "\""
                + ",\"endsAt\":\"" + SERIES_END + "\""
                + ",\"timezone\":\"" + REQUESTED_ZONE.getId() + "\""
                + ",\"frequency\":\"WEEKLY\""
                + ",\"occurrences\":" + OCCURRENCES + "}";
        return objectMapper.readValue(json, ReservationSeriesInput.class);
    }

    /**
     * Resource port: every Optional-returning lookup resolves to the active meeting room without an
     * approval requirement, so the scenario preconditions hold.
     */
    private ResourceRepository resourceRepository(BookableResource room) {
        return mock(ResourceRepository.class, invocation -> {
            if (Optional.class.equals(invocation.getMethod().getReturnType())) {
                return Optional.of(room);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /**
     * Reservation port: records every reservation the service persists (single or batch save) and
     * reports no conflicting reservations, keeping the recurring window conflict-free.
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
}
