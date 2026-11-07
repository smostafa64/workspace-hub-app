package com.example.workspace.service;

import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Unit-layer verification of the reservation-series occurrence expansion across IANA zones and
 * calendar boundaries.
 *
 * <p>Each test drives the real {@link ReservationService} in process with a fixed injected
 * {@link Clock}. The repository interfaces are mocked only as the persistence boundary; the
 * weekly local-time expansion, the zone resolution and the occurrence validation are production
 * code. The expansion is observed both from the returned {@link ReservationSeriesView} and from
 * the exact {@link Reservation} rows handed to the persistence boundary, so "returns the
 * requested count" is not inferred from a size alone.</p>
 *
 * <p>All calendar boundaries are derived at run time from the current date rather than frozen
 * literals: the first day of next month, the next first of January, and the next 29 February.
 * The injected clock for every expansion is fixed one day before the first occurrence, so the
 * whole twelve-week window is genuinely future relative to the clock the service observes and
 * the tests never depend on a stale year or on shared fixture availability.</p>
 */
class ReservationSeriesExpansionBoundaryUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final String WEEKLY = "WEEKLY";
    private static final int OCCURRENCES = 12;
    private static final long RESOURCE_ID = 1L;
    private static final String TITLE = "QA expansion boundary series";
    private static final LocalTime LOCAL_START_TIME = LocalTime.of(9, 0);

    /** Runtime-derived boundaries: never a frozen literal year or month. */
    private static final LocalDate TODAY = LocalDate.now(BERLIN);
    private static final LocalDate MONTH_BOUNDARY =
            TODAY.with(TemporalAdjusters.firstDayOfNextMonth());
    private static final LocalDate YEAR_BOUNDARY = LocalDate.of(TODAY.getYear() + 1, 1, 1);
    private static final LocalDate NEXT_LEAP_DAY = nextLeapDayOnOrAfter(TODAY);

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final List<Reservation> savedRows = new ArrayList<>();

    private ReservationService service;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        savedRows.clear();

        BookableResource resource = new BookableResource(
                "QA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "QA expansion room", ResourceType.ROOM, "HQ", 3, 12, true, false);
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);

        when(resources.findLockedById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(bookings.existsConflictingReservation(anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation row = invocation.getArgument(0);
            savedRows.add(row);
            return row;
        });
    }

    // ---------------------------------------------------------------------
    // SCN-021#A1 - exactly twelve weekly occurrences, same local clock time
    // ---------------------------------------------------------------------
    @Test
    void scn021a1TwelveWeeklyOccurrencesKeepLocalWallClockInBerlin() {
        ZonedDateTime firstLocal = ZonedDateTime.of(
                TODAY.plusWeeks(1).atTime(LOCAL_START_TIME), BERLIN);

        assertTwelveWeeklyOccurrences(BERLIN, firstLocal, "Europe/Berlin");
    }

    @Test
    void scn021a1TwelveWeeklyOccurrencesKeepLocalWallClockInSydney() {
        ZonedDateTime firstLocal = ZonedDateTime.of(
                LocalDate.now(SYDNEY).plusWeeks(1).atTime(LOCAL_START_TIME), SYDNEY);

        assertTwelveWeeklyOccurrences(SYDNEY, firstLocal, "Australia/Sydney");
    }

    // ---------------------------------------------------------------------
    // SCN-021#A2 - month, year and leap-day boundaries keep weekly local spacing
    // ---------------------------------------------------------------------
    @Test
    void scn021a2ExpansionAcrossMonthBoundaryKeepsWeeklyLocalSpacing() {
        LocalDate firstDate = MONTH_BOUNDARY.minusWeeks(2);
        ZonedDateTime firstLocal = ZonedDateTime.of(firstDate.atTime(LOCAL_START_TIME), BERLIN);

        // Control: the derived twelve-week window really crosses a calendar month boundary.
        assertTrue(!YearMonth.from(firstDate).equals(YearMonth.from(firstDate.plusWeeks(2))),
                "control: the derived window must actually cross the month boundary");

        assertTwelveWeeklyOccurrences(BERLIN, firstLocal, "Europe/Berlin");
    }

    @Test
    void scn021a2ExpansionAcrossYearBoundaryKeepsWeeklyLocalSpacing() {
        LocalDate firstDate = YEAR_BOUNDARY.minusWeeks(2);
        ZonedDateTime firstLocal = ZonedDateTime.of(firstDate.atTime(LOCAL_START_TIME), BERLIN);

        // Control: the derived twelve-week window really crosses a calendar year boundary.
        assertTrue(!Year.from(firstDate).equals(Year.from(firstDate.plusWeeks(2))),
                "control: the derived window must actually cross the calendar year boundary");

        // Control: the window also crosses the ISO week-of-year boundary (week 52/53 to week 1).
        long distinctWeekBasedYears = IntStream.range(0, OCCURRENCES)
                .mapToObj(index -> firstDate.plusWeeks(index))
                .map(date -> date.get(WeekFields.ISO.weekBasedYear()))
                .distinct()
                .count();
        assertTrue(distinctWeekBasedYears >= 2,
                "control: the twelve weekly occurrences must span at least two ISO week-based years");

        assertTwelveWeeklyOccurrences(BERLIN, firstLocal, "Europe/Berlin");
    }

    @Test
    void scn021a2ExpansionAcrossLeapDayKeepsWeeklyLocalSpacing() {
        LocalDate firstDate = NEXT_LEAP_DAY.minusWeeks(2);
        ZonedDateTime firstLocal = ZonedDateTime.of(firstDate.atTime(LOCAL_START_TIME), BERLIN);

        // Control: the derived window really contains the 29 February calendar date.
        assertEquals(NEXT_LEAP_DAY, firstDate.plusWeeks(2),
                "control: occurrence three must land exactly on the derived 29 February");
        assertEquals(29, NEXT_LEAP_DAY.getDayOfMonth(),
                "control: the derived leap day must be 29 February");
        assertEquals(2, NEXT_LEAP_DAY.getMonthValue(),
                "control: the derived leap day must fall in February");

        assertTwelveWeeklyOccurrences(BERLIN, firstLocal, "Europe/Berlin");
    }

    // ---------------------------------------------------------------------
    // Shared expansion assertion
    // ---------------------------------------------------------------------

    private void assertTwelveWeeklyOccurrences(ZoneId zone, ZonedDateTime firstLocal,
                                               String timezoneName) {
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        // Fixed injected clock one day before the first occurrence: the whole window is future.
        Instant clockInstant = firstLocal.toInstant().minus(Duration.ofDays(1));
        Clock fixedClock = Clock.fixed(clockInstant, ZoneOffset.UTC);
        assertEquals(clockInstant, fixedClock.instant(),
                "control: the expansion must be driven by the fixed injected clock");
        assertTrue(firstLocal.toInstant().isAfter(fixedClock.instant()),
                "control: the first occurrence must start in the future relative to the injected clock");

        service = new ReservationService(resources, bookings, audits, fixedClock);

        ReservationSeriesView view = service.createSeries(
                input(startsAt, endsAt, timezoneName, OCCURRENCES), "alice");

        assertEquals(OCCURRENCES, view.occurrences(),
                "the expansion must return exactly the requested occurrence count, but saw "
                        + view.occurrences());
        assertEquals(OCCURRENCES, view.reservations().size(),
                "the expansion must expose exactly twelve reservations, but saw "
                        + view.reservations().size());
        assertEquals(OCCURRENCES, savedRows.size(),
                "the expansion must hand exactly twelve rows to the persistence boundary, but saw "
                        + savedRows.size());
        assertEquals(OCCURRENCES,
                savedRows.stream().map(Reservation::getStartsAt).distinct().count(),
                "the twelve saved occurrences must occupy twelve distinct start instants");

        LocalTime expectedLocalTime = firstLocal.toLocalTime();
        LocalDate previousDate = null;
        for (int index = 0; index < OCCURRENCES; index++) {
            ZonedDateTime occurrence = view.reservations().get(index).startsAt().atZone(zone);

            assertEquals(expectedLocalTime, occurrence.toLocalTime(),
                    "occurrence " + (index + 1) + " must keep the first occurrence's local clock "
                            + "time " + expectedLocalTime + ", but saw " + occurrence.toLocalTime());
            if (previousDate != null) {
                assertEquals(7, ChronoUnit.DAYS.between(previousDate, occurrence.toLocalDate()),
                        "occurrence " + (index + 1) + " must fall exactly seven local days after "
                                + "occurrence " + index + ", but fell "
                                + ChronoUnit.DAYS.between(previousDate, occurrence.toLocalDate())
                                + " days after");
            }
            previousDate = occurrence.toLocalDate();
        }

        // The persistence boundary receives rows with the same local wall clock and spacing, so
        // the result is not only a view projection.
        for (int index = 0; index < savedRows.size(); index++) {
            ZonedDateTime row = savedRows.get(index).getStartsAt().atZone(zone);
            assertEquals(expectedLocalTime, row.toLocalTime(),
                    "saved row " + (index + 1) + " must carry the same local clock time, but saw "
                            + row.toLocalTime());
            assertEquals(view.reservations().get(index).startsAt(),
                    savedRows.get(index).getStartsAt(),
                    "saved row " + (index + 1) + " must match the reported occurrence instant");
        }
    }

    private ReservationSeriesInput input(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                        String timezone, int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt, endsAt,
                timezone, WEEKLY, occurrences);

        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset and local time");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain the supplied offset and local time");
        assertEquals(timezone, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(WEEKLY, input.frequency(),
                "input control: the frequency must be WEEKLY");
        assertEquals(occurrences, input.occurrences().intValue(),
                "input control: the requested occurrence count must be retained");
        return input;
    }

    /** Next 29 February on or after the supplied date, derived without a frozen literal year. */
    private static LocalDate nextLeapDayOnOrAfter(LocalDate from) {
        int year = from.getYear();
        while (true) {
            if (Year.isLeap(year)) {
                LocalDate candidate = LocalDate.of(year, 2, 29);
                if (!candidate.isBefore(from)) {
                    return candidate;
                }
            }
            year++;
        }
    }
}
