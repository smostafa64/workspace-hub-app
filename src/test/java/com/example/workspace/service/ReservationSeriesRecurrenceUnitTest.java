package com.example.workspace.service;

import com.example.workspace.api.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-layer verification for the reservation-series recurrence expansion and occurrence
 * validation.
 *
 * <p>Each test invokes the real {@link ReservationService} directly. The repository interfaces are
 * the only mocked collaborators: they are the persistence boundary, not the logic under test. The
 * service's recurrence expansion, zone resolution, boundary validation and rejection behaviour all
 * run as production code. No business rule is reimplemented in the test.</p>
 *
 * <p>The injected {@link Clock} is fixed, but it is not tied to a frozen calendar date: the next
 * future Europe/Berlin spring-forward transition is derived from the zone rules at class load time
 * and the clock is placed six weeks before it. The weekly window used by the DST test therefore
 * always really spans a future transition, and the test cannot go stale as the calendar advances.
 * The repository mock records every {@link Reservation} the service asks to persist, so "creates N
 * rows" is observed from the service's actual save calls rather than from a returned size only.</p>
 */
class ReservationSeriesRecurrenceUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final long RESOURCE_ID = 1L;
    private static final String TITLE = "QA recurrence series";

    /**
     * Next future Europe/Berlin spring-forward transition, derived at run time from the zone rules
     * instead of a hard-coded year.
     */
    private static final ZoneOffsetTransition SPRING_TRANSITION = nextBerlinSpringTransition();

    /**
     * Fixed injected clock placed six weeks before the derived transition, so the transition is
     * genuinely in the future relative to the service's clock.
     */
    private static final Instant FIXED_INSTANT =
            SPRING_TRANSITION.getInstant().minus(Duration.ofDays(42));

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final List<Reservation> savedRows = new ArrayList<>();

    private ReservationService service;
    private BookableResource resource;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        savedRows.clear();
        service = new ReservationService(resources, bookings, audits,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        resource = new BookableResource("QA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "QA recurrence room", ResourceType.ROOM, "HQ", 2, 10, true, false);
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
    // SCN-002#A1
    // ---------------------------------------------------------------------
    @Test
    void scn002WeeklyExpansionRendersEveryOccurrenceAtTheSameBerlinWallClock() {
        LocalDate transitionDate = SPRING_TRANSITION.getDateTimeBefore().toLocalDate();
        ZonedDateTime firstLocal = ZonedDateTime.of(
                transitionDate.minusWeeks(2).atTime(9, 0), BERLIN);
        ZonedDateTime lastLocal = firstLocal.plusWeeks(2);

        // Control: the owned window is genuinely future and genuinely spans the derived transition.
        assertTrue(firstLocal.toInstant().isAfter(FIXED_INSTANT),
                "control: the first occurrence must start in the future relative to the injected clock");
        assertTrue(SPRING_TRANSITION.getInstant().isAfter(firstLocal.toInstant())
                        && SPRING_TRANSITION.getInstant().isBefore(lastLocal.toInstant()),
                "control: the three weekly occurrences must really span the derived DST transition");
        assertNotEquals(firstLocal.getOffset(), lastLocal.getOffset(),
                "control: the derived DST transition must change the Europe/Berlin offset across the window");

        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        ReservationSeriesView view = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 3), "alice");

        assertEquals(3, view.occurrences(),
                "control: the series must request three occurrences");
        assertEquals(3, view.reservations().size(),
                "the expansion must expose every requested occurrence, but saw "
                        + view.reservations().size());
        assertEquals(3, savedRows.size(),
                "the expansion must hand exactly three reservation rows to the persistence boundary, "
                        + "but saw " + savedRows.size());

        ZonedDateTime first = view.reservations().get(0).startsAt().atZone(BERLIN);
        assertEquals(LocalTime.of(9, 0), first.toLocalTime(),
                "control: the first occurrence must start at the requested 09:00 Europe/Berlin wall clock");

        for (int index = 1; index < view.reservations().size(); index++) {
            ZonedDateTime occurrence = view.reservations().get(index).startsAt().atZone(BERLIN);
            assertEquals(first.toLocalTime(), occurrence.toLocalTime(),
                    "occurrence " + (index + 1) + " must render the same Europe/Berlin wall-clock time "
                            + "as the first occurrence, but saw " + occurrence.toLocalTime());
            assertEquals(first.toLocalDateTime().plusWeeks(index), occurrence.toLocalDateTime(),
                    "occurrence " + (index + 1) + " must keep the same local clock time one local week "
                            + "after the previous one");
        }

        // If the implementation had added whole UTC weeks, the post-transition occurrence would
        // render at 10:00 local instead of 09:00. This differential control proves the window
        // actually exercises the DST transition.
        assertNotEquals(
                view.reservations().get(0).startsAt().atZone(BERLIN).getOffset(),
                view.reservations().get(2).startsAt().atZone(BERLIN).getOffset(),
                "control: the persisted instants must fall on opposite sides of the derived transition");
    }

    // ---------------------------------------------------------------------
    // SCN-003#A1
    // ---------------------------------------------------------------------
    @Test
    void scn003TwoOccurrencesCreateTwoReservations() {
        assertOccurrenceCountIsExpanded(2);
    }

    @Test
    void scn003TwelveOccurrencesCreateTwelveReservations() {
        assertOccurrenceCountIsExpanded(12);
    }

    private void assertOccurrenceCountIsExpanded(int occurrences) {
        OffsetDateTime startsAt = standardFutureStart();

        ReservationSeriesView view = service.createSeries(
                input(startsAt, startsAt.plusHours(1), occurrences), "alice");

        assertEquals(occurrences, view.occurrences(),
                "the series view must report exactly " + occurrences + " occurrences");
        assertEquals(occurrences, view.reservations().size(),
                "the expansion must expose exactly " + occurrences + " reservations, but saw "
                        + view.reservations().size());
        assertEquals(occurrences, savedRows.size(),
                "the expansion must hand exactly " + occurrences + " rows to the persistence "
                        + "boundary, but saw " + savedRows.size());
        assertEquals(occurrences,
                savedRows.stream().map(Reservation::getStartsAt).distinct().count(),
                "the " + occurrences + " saved rows must occupy distinct start instants rather than "
                        + "repeating one slot");
        assertTrue(savedRows.stream().allMatch(row -> row.getStartsAt().isAfter(FIXED_INSTANT)),
                "every saved occurrence must start in the future relative to the injected clock");

        List<Reservation> rows = List.copyOf(savedRows);
        LocalDate firstDate = rows.get(0).getStartsAt().atZone(BERLIN).toLocalDate();
        LocalTime firstTime = rows.get(0).getStartsAt().atZone(BERLIN).toLocalTime();
        for (int index = 1; index < rows.size(); index++) {
            ZonedDateTime occurrence = rows.get(index).getStartsAt().atZone(BERLIN);
            assertEquals(firstTime, occurrence.toLocalTime(),
                    "control: occurrence " + (index + 1) + " must keep the first occurrence's local "
                            + "clock time");
            assertEquals(firstDate.plusWeeks(index), occurrence.toLocalDate(),
                    "control: occurrence " + (index + 1) + " must fall one local week after occurrence "
                            + index);
        }
    }

    // ---------------------------------------------------------------------
    // SCN-004#A1
    // ---------------------------------------------------------------------
    @Test
    void scn004FifteenMinuteOccurrenceIsAccepted() {
        OffsetDateTime startsAt = standardFutureStart();
        OffsetDateTime endsAt = startsAt.plusMinutes(15);

        ReservationSeriesView view = service.createSeries(
                input(startsAt, endsAt, 2), "alice");

        assertEquals(2, view.reservations().size(),
                "a 15 minute occurrence is within the allowed duration and must be accepted");
        assertEquals(2, savedRows.size(),
                "both 15 minute occurrences must be handed to the persistence boundary, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofMinutes(15))),
                "control: the accepted occurrences must really have a 15 minute duration");
        assertTrue(savedRows.stream().allMatch(row -> row.getStartsAt().isAfter(FIXED_INSTANT)),
                "every accepted occurrence must start in the future relative to the injected clock");
    }

    @Test
    void scn004EightHourOccurrenceIsAccepted() {
        OffsetDateTime startsAt = standardFutureStart();
        OffsetDateTime endsAt = startsAt.plusHours(8);

        ReservationSeriesView view = service.createSeries(
                input(startsAt, endsAt, 2), "alice");

        assertEquals(2, view.reservations().size(),
                "an 8 hour occurrence is within the allowed duration and must be accepted");
        assertEquals(2, savedRows.size(),
                "both 8 hour occurrences must be handed to the persistence boundary, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofHours(8))),
                "control: the accepted occurrences must really have an 8 hour duration");
        assertTrue(savedRows.stream().allMatch(row -> row.getStartsAt().isAfter(FIXED_INSTANT)),
                "every accepted occurrence must start in the future relative to the injected clock");
    }

    // ---------------------------------------------------------------------
    // SCN-004#A2
    // ---------------------------------------------------------------------
    @Test
    void scn004FourteenMinuteOccurrenceIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(input(startsAt, startsAt.plusMinutes(14), 2), "alice"),
                "a 14 minute occurrence is below the 15 minute lower bound and must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "a too-short occurrence must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected 14 minute series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: the identical request differs only in the duration (15 minutes).
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusMinutes(15), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same request at exactly 15 minutes must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must be saved");
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofMinutes(15))),
                "valid control: the accepted rows must have the intended 15 minute duration");
    }

    @Test
    void scn004EightHourOneMinuteOccurrenceIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(input(startsAt, startsAt.plusHours(8).plusMinutes(1), 2),
                        "alice"),
                "an 8 hour 1 minute occurrence exceeds the 8 hour upper bound and must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "an over-long occurrence must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected 8 hour 1 minute series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: the identical request differs only in the duration (8 hours).
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(8), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same request at exactly 8 hours must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must be saved");
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofHours(8))),
                "valid control: the accepted rows must have the intended 8 hour duration");
    }

    @Test
    void scn004EndsAtEarlierThanStartsAtIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(input(startsAt, startsAt.minusHours(1), 2), "alice"),
                "an endsAt earlier than startsAt must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "an inverted interval must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected inverted series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: the identical request differs only in the endsAt ordering.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same request with endsAt after startsAt must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must be saved");
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------

    /**
     * A future local start four weeks after the derived spring transition and well before the
     * following autumn transition, so the boundary tests are not perturbed by a DST change.
     */
    private static OffsetDateTime standardFutureStart() {
        LocalDate transitionDate = SPRING_TRANSITION.getDateTimeBefore().toLocalDate();
        return ZonedDateTime.of(transitionDate.plusWeeks(4).atTime(9, 0), BERLIN).toOffsetDateTime();
    }

    private ReservationSeriesInput input(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                        int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain the supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(occurrences, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return input;
    }

    /** Derives the next future Europe/Berlin spring-forward transition from the zone rules. */
    private static ZoneOffsetTransition nextBerlinSpringTransition() {
        ZoneRules rules = BERLIN.getRules();
        ZoneOffsetTransition candidate = rules.nextTransition(Instant.now().plus(Duration.ofDays(1)));
        for (int attempt = 0; attempt < 4 && candidate != null; attempt++) {
            if (candidate.getOffsetAfter().getTotalSeconds()
                    > candidate.getOffsetBefore().getTotalSeconds()) {
                return candidate;
            }
            candidate = rules.nextTransition(candidate.getInstant());
        }
        throw new AssertionError("no future Europe/Berlin spring-forward transition was found");
    }
}
