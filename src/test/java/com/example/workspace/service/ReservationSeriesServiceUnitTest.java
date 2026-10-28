package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-unit verification of the recurring-series write rules at the documented service
 * boundary {@link ReservationService#createSeries(ReservationSeriesInput, String)}.
 *
 * <p>The request is materialised from the documented JSON wire contract through the same Jackson
 * binding the application uses for the request body, so the unit never invents a DTO constructor
 * or an expected value: the frozen fixtures are the documented series payloads. The repositories
 * are isolated Mockito collaborators (an active, free Room 101, no conflicts, save echoes the
 * entity) and the clock is fixed at 2026-03-01T00:00:00Z, so every fixture window is in the
 * future.</p>
 *
 * <p>Frozen Europe/Berlin expectations exercised here: a local start of 2026-03-29T02:30 does not
 * exist and resolves to 03:30 local = 2026-03-29T01:30:00Z; it is kept, not skipped.</p>
 */
class ReservationSeriesServiceUnitTest {

    private static final Long ROOM_101_ID = 101L;
    private static final String BERLIN = "Europe/Berlin";
    private static final String UTC = "UTC";
    private static final ZoneId BERLIN_ZONE = ZoneId.of(BERLIN);
    private static final ZoneId UTC_ZONE = ZoneId.of(UTC);

    /** Frozen 2026-03-01 test clock: the March 2026 acceptance window and all 2030 fixtures are future. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    private static final String VALID_START = "2030-05-06T10:00:00+02:00";
    private static final String VALID_END = "2030-05-06T11:00:00+02:00";

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);

    private ReservationService service;
    private BookableResource room101;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        service = new ReservationService(resources, bookings, audits, CLOCK);

        room101 = new BookableResource("R101", "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false);
        ReflectionTestUtils.setField(room101, "id", ROOM_101_ID);
        when(resources.findLockedById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(resources.findById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * SCN-003#A1 - A fixed-offset identifier is not an IANA area/location identifier, so the guard
     * rejects it with 400 before any occurrence is saved.
     */
    @Test
    void scn003A1FixedOffsetTimezoneIsRejectedBeforeAnyBooking() {
        assertTimezoneRejected("+01:00");
    }

    /**
     * SCN-003#A2 - The offset-bearing abbreviation GMT+01:00 and the bare abbreviation CET are each
     * rejected with 400 before any occurrence is saved.
     */
    @Test
    void scn003A2AbbreviatedTimezoneIdentifiersAreRejectedBeforeAnyBooking() {
        assertTimezoneRejected("GMT+01:00");
        assertTimezoneRejected("CET");
    }

    /**
     * SCN-003#A3 - The IANA identifier Europe/Berlin and the exact identifier UTC are each accepted
     * and the series is created with its occurrences at the requested local wall-clock time.
     */
    @Test
    void scn003A3IanaAndUtcIdentifiersAreAcceptedAndCreateTheSeries() {
        ReservationSeriesView berlin = createSeries(BERLIN, VALID_START, VALID_END, 2);
        assertEquals(2, berlin.reservations().size(),
                "timezone=Europe/Berlin must be accepted and create both occurrences, but got "
                        + berlin.reservations());
        assertEquals(BERLIN, berlin.timezone(),
                "the created series must echo the accepted IANA identifier");
        for (ReservationView reservation : berlin.reservations()) {
            assertEquals(LocalTime.of(10, 0), reservation.startsAt().atZone(BERLIN_ZONE).toLocalTime(),
                    "timezone=Europe/Berlin must keep the 10:00 local start, but got " + reservation.startsAt());
        }

        ReservationSeriesView utc = createSeries(UTC, "2030-05-06T10:00:00Z", "2030-05-06T11:00:00Z", 2);
        assertEquals(2, utc.reservations().size(),
                "timezone=UTC must be accepted and create both occurrences, but got " + utc.reservations());
        assertEquals(UTC, utc.timezone(),
                "the created series must echo the accepted exact identifier UTC");
        for (ReservationView reservation : utc.reservations()) {
            assertEquals(LocalTime.of(10, 0), reservation.startsAt().atZone(UTC_ZONE).toLocalTime(),
                    "timezone=UTC must keep the 10:00 local start, but got " + reservation.startsAt());
        }
    }

    /**
     * SCN-004#A1 - The inclusive occurrence bounds 2 and 12 create exactly two and twelve
     * occurrences respectively.
     */
    @Test
    void scn004A1OccurrenceBoundsCreateTheRequestedNumberOfOccurrences() {
        ReservationSeriesView two = createSeries(BERLIN, "2030-06-03T10:00:00+02:00",
                "2030-06-03T11:00:00+02:00", 2);
        assertEquals(2, two.reservations().size(),
                "occurrences=2 must create exactly two occurrences, but got " + two.reservations());

        ReservationSeriesView twelve = createSeries(BERLIN, "2030-06-03T10:00:00+02:00",
                "2030-06-03T11:00:00+02:00", 12);
        assertEquals(12, twelve.reservations().size(),
                "occurrences=12 must create exactly twelve occurrences, but got " + twelve.reservations());
    }

    /**
     * SCN-004#A2 - A whole number below or above the 2-12 range is rejected with 400 before any
     * occurrence is saved.
     */
    @Test
    void scn004A2OccurrenceCountsOutsideTwoToTwelveAreRejected() {
        assertOccurrencesRejected(1);
        assertOccurrencesRejected(13);
    }

    /**
     * SCN-004#A4 - occurrences=5 creates exactly five occurrences, one per local week at the same
     * 10:00 Europe/Berlin wall-clock time.
     */
    @Test
    void scn004A4FiveOccurrencesCreateFiveWeeklyOccurrences() {
        ReservationSeriesView series = createSeries(BERLIN, "2030-06-03T10:00:00+02:00",
                "2030-06-03T11:00:00+02:00", 5);
        List<ReservationView> reservations = sortedByStart(series);
        assertEquals(5, reservations.size(),
                "occurrences=5 must create exactly five weekly occurrences, but got " + reservations);

        List<LocalDate> localDates = new ArrayList<>();
        for (ReservationView reservation : reservations) {
            ZonedDateTime localStart = reservation.startsAt().atZone(BERLIN_ZONE);
            assertEquals(LocalTime.of(10, 0), localStart.toLocalTime(),
                    "every weekly occurrence must keep the 10:00 local start, but got " + localStart);
            localDates.add(localStart.toLocalDate());
        }
        for (int i = 1; i < localDates.size(); i++) {
            assertEquals(7, ChronoUnit.DAYS.between(localDates.get(i - 1), localDates.get(i)),
                    "consecutive occurrences must be exactly seven local days apart, but got " + localDates);
        }
    }

    /**
     * SCN-005#A1 - The nonexistent local start 2026-03-29T02:30 Europe/Berlin is kept and resolves
     * to 03:30 local, i.e. the frozen instant 2026-03-29T01:30:00Z.
     */
    @Test
    void scn005A1SpringForwardLocalStartResolvesTo0330BerlinAnd0130Z() {
        ReservationSeriesView series = createSeries(BERLIN, "2026-03-29T02:30:00+01:00",
                "2026-03-29T04:00:00+02:00", 2);

        Instant expectedInstant = Instant.parse("2026-03-29T01:30:00Z");
        ReservationView shifted = series.reservations().stream()
                .filter(reservation -> expectedInstant.equals(reservation.startsAt()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the 2026-03-29 occurrence must be kept at the shifted instant " + expectedInstant
                                + ", but the series returned " + describe(series.reservations())));

        ZonedDateTime localStart = shifted.startsAt().atZone(BERLIN_ZONE);
        assertEquals(LocalDate.parse("2026-03-29"), localStart.toLocalDate(),
                "the shifted occurrence must stay on 2026-03-29, but resolved to " + localStart);
        assertEquals(LocalTime.of(3, 30), localStart.toLocalTime(),
                "the nonexistent local 02:30 must resolve to 03:30 Europe/Berlin, but resolved to " + localStart);
        assertEquals(expectedInstant, shifted.startsAt(),
                "03:30 local Europe/Berlin on 2026-03-29 is the frozen instant 2026-03-29T01:30:00Z");
    }

    /**
     * SCN-005#A2 - The shifted spring-forward occurrence is created rather than rejected or skipped:
     * the requested two occurrences are returned, including the frozen 2026-03-29T01:30:00Z start.
     */
    @Test
    void scn005A2ShiftedSpringForwardOccurrenceIsCreatedNotSkipped() {
        ReservationSeriesView series = createSeries(BERLIN, "2026-03-29T02:30:00+01:00",
                "2026-03-29T04:00:00+02:00", 2);

        assertEquals(2, series.reservations().size(),
                "the shifted occurrence must not be dropped, so both requested occurrences must be created, "
                        + "but the series returned " + describe(series.reservations()));
        assertTrue(series.reservations().stream()
                        .anyMatch(reservation -> Instant.parse("2026-03-29T01:30:00Z")
                                .equals(reservation.startsAt())),
                "the shifted 2026-03-29 occurrence must be present in the created series, but the series "
                        + "returned " + describe(series.reservations()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ReservationSeriesView createSeries(String timezone, String startsAt, String endsAt,
                                               int occurrences) {
        ReservationSeriesView series = service.createSeries(
                input(startsAt, endsAt, timezone, "WEEKLY", occurrences), "alice");
        assertNotNull(series, "a valid series request must return the created series");
        return series;
    }

    private void assertTimezoneRejected(String timezone) {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createSeries(input(VALID_START, VALID_END, timezone, "WEEKLY", 3), "alice"),
                "timezone=" + timezone + " is not an IANA Area/Location identifier and must be rejected");
        assertEquals(HttpStatus.BAD_REQUEST, error.status(),
                "timezone=" + timezone + " must be rejected with 400, but the status was " + error.status());
        verify(bookings, never()).save(any(Reservation.class));
    }

    private void assertOccurrencesRejected(int occurrences) {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createSeries(input("2030-06-03T10:00:00+02:00", "2030-06-03T11:00:00+02:00",
                        BERLIN, "WEEKLY", occurrences), "alice"),
                "occurrences=" + occurrences + " is outside the 2-12 range and must be rejected");
        assertEquals(HttpStatus.BAD_REQUEST, error.status(),
                "occurrences=" + occurrences + " must be rejected with 400, but the status was " + error.status());
        verify(bookings, never()).save(any(Reservation.class));
    }

    /**
     * Materialises the documented series request body through the application's JSON binding, so no
     * DTO constructor shape is assumed beyond the frozen wire contract.
     */
    private static ReservationSeriesInput input(String startsAt, String endsAt, String timezone,
                                                String frequency, int occurrences) {
        String body = """
                {
                  "resourceId": %d,
                  "title": "Weekly planning",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "timezone": "%s",
                  "frequency": "%s",
                  "occurrences": %d
                }
                """.formatted(ROOM_101_ID, startsAt, endsAt, timezone, frequency, occurrences);
        try {
            return JSON.readValue(body, ReservationSeriesInput.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("the documented series body could not be bound: " + body, exception);
        }
    }

    private static List<ReservationView> sortedByStart(ReservationSeriesView series) {
        return series.reservations().stream()
                .sorted(Comparator.comparing(ReservationView::startsAt))
                .toList();
    }

    private static String describe(List<ReservationView> reservations) {
        return reservations.stream()
                .map(reservation -> "[startsAt=" + reservation.startsAt() + "]")
                .toList()
                .toString();
    }
}
