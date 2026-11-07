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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Unit-layer verification of the frozen recurrence-expansion, occurrence-count and
 * occurrence-duration obligations for the reservation-series feature.
 *
 * <p>Every test invokes the real {@link ReservationService#createSeries} in process. Only the
 * repository interfaces (the persistence boundary) are mocked; the weekly local-wall-clock
 * expansion, the timezone resolution, the occurrence-count gate and the duration gate all execute
 * as production code. The repository mock records every {@link Reservation} the service asks to
 * persist, so "creates N rows" is observed from the service's real save calls and not merely from
 * the returned view size.</p>
 *
 * <p>The injected {@link Clock} is fixed relative to the next future Europe/Berlin spring-forward
 * transition, which is derived from the zone rules at class load time. The DST test therefore
 * really spans a future transition without a frozen calendar date, and the boundary tests use a
 * genuinely future window relative to the clock the service observes.</p>
 */
class ReservationSeriesValidationUnitObligationsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final long RESOURCE_ID = 1L;
    private static final String TITLE = "QA series unit obligations";

    /** Next future Europe/Berlin spring-forward transition, derived at run time. */
    private static final ZoneOffsetTransition SPRING_TRANSITION = nextBerlinSpringTransition();

    /** Fixed injected clock six weeks before the derived transition. */
    private static final Instant FIXED_NOW = SPRING_TRANSITION.getInstant().minus(Duration.ofDays(42));

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final List<Reservation> savedRows = new ArrayList<>();

    private ReservationService service;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        savedRows.clear();

        service = new ReservationService(resources, bookings, audits,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        BookableResource resource = new BookableResource(
                "QA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "QA series unit room", ResourceType.ROOM, "HQ", 2, 10, true, false);
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
    // SCN-002#A1 - every occurrence expands to the requested local wall clock
    // ---------------------------------------------------------------------
    @Test
    void scn002ExpansionReturnsRequestedWallClockInstantsAcrossDst() {
        LocalDate transitionDate = SPRING_TRANSITION.getDateTimeBefore().toLocalDate();
        ZonedDateTime firstRequested = ZonedDateTime.of(
                transitionDate.minusWeeks(2).atTime(9, 0), BERLIN);
        ZonedDateTime lastRequested = firstRequested.plusWeeks(2);

        // Controls: the derived window is genuinely future and genuinely spans the transition.
        assertTrue(firstRequested.toInstant().isAfter(FIXED_NOW),
                "control: the first occurrence must start in the future relative to the injected clock");
        assertTrue(SPRING_TRANSITION.getInstant().isAfter(firstRequested.toInstant())
                        && SPRING_TRANSITION.getInstant().isBefore(lastRequested.toInstant()),
                "control: the three weekly occurrences must really span the derived DST transition");
        assertNotEquals(firstRequested.getOffset(), lastRequested.getOffset(),
                "control: the derived transition must change the Europe/Berlin offset across the window");

        OffsetDateTime startsAt = firstRequested.toOffsetDateTime();
        ReservationSeriesView view = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 3), "alice");

        assertEquals(3, view.occurrences(),
                "control: the series must request three occurrences");
        assertEquals(3, view.reservations().size(),
                "the expansion must expose every requested occurrence, but saw "
                        + view.reservations().size());
        assertEquals(3, savedRows.size(),
                "the expansion must hand exactly three rows to the persistence boundary, but saw "
                        + savedRows.size());

        for (int index = 0; index < 3; index++) {
            ZonedDateTime expectedLocal = firstRequested.plusWeeks(index);
            Instant occurrenceInstant = view.reservations().get(index).startsAt();
            ZonedDateTime rendered = occurrenceInstant.atZone(BERLIN);

            assertEquals(expectedLocal.toLocalDateTime(), rendered.toLocalDateTime(),
                    "occurrence " + (index + 1) + " must render the requested wall-clock start "
                            + expectedLocal.toLocalDateTime() + " in the supplied zone, but saw "
                            + rendered.toLocalDateTime());
            assertEquals(firstRequested.toLocalTime(), rendered.toLocalTime(),
                    "occurrence " + (index + 1) + " must keep the requested local start time "
                            + firstRequested.toLocalTime() + ", but saw " + rendered.toLocalTime());
            assertEquals(occurrenceInstant, savedRows.get(index).getStartsAt(),
                    "saved row " + (index + 1) + " must carry the reported occurrence instant");
        }

        // Differential control: whole-UTC-week expansion would render the post-transition
        // occurrence at a different local clock time; the offsets must really differ.
        assertNotEquals(
                view.reservations().get(0).startsAt().atZone(BERLIN).getOffset(),
                view.reservations().get(2).startsAt().atZone(BERLIN).getOffset(),
                "control: the persisted instants must fall on opposite sides of the derived transition");
    }

    // ---------------------------------------------------------------------
    // SCN-003#A1 - occurrences 2 and 12 accepted, 1 and 13 rejected
    // ---------------------------------------------------------------------
    @Test
    void scn003TwoOccurrencesAreAccepted() {
        assertOccurrenceCountAccepted(2);
    }

    @Test
    void scn003TwelveOccurrencesAreAccepted() {
        assertOccurrenceCountAccepted(12);
    }

    @Test
    void scn003OneOccurrenceIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(input(startsAt, startsAt.plusHours(1), 1), "alice"),
                "occurrences 1 is immediately below the accepted range and must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "occurrences 1 must be rejected as a client error, but saw " + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected occurrences 1 series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: identical request, only the count changes to the boundary value 2.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: occurrences 2 must be accepted for the identical request");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two accepted occurrences must reach persistence, but saw "
                        + savedRows.size());
    }

    @Test
    void scn003ThirteenOccurrencesAreRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(input(startsAt, startsAt.plusHours(1), 13), "alice"),
                "occurrences 13 is immediately above the accepted range and must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "occurrences 13 must be rejected as a client error, but saw " + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected occurrences 13 series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: identical request, only the count changes to the boundary value 12.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 12), "alice");
        assertEquals(12, control.reservations().size(),
                "valid control: occurrences 12 must be accepted for the identical request");
        assertEquals(12, savedRows.size(),
                "valid control: exactly the twelve accepted occurrences must reach persistence, but "
                        + "saw " + savedRows.size());
    }

    private void assertOccurrenceCountAccepted(int occurrences) {
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
        assertTrue(savedRows.stream().allMatch(row -> row.getStartsAt().isAfter(FIXED_NOW)),
                "every accepted occurrence must start in the future relative to the injected clock");
    }

    // ---------------------------------------------------------------------
    // SCN-005#A1 - 15 minutes and 8 hours accepted, 14 minutes and 8h1m rejected
    // ---------------------------------------------------------------------
    @Test
    void scn005FifteenMinuteOccurrenceIsAccepted() {
        OffsetDateTime startsAt = standardFutureStart();

        ReservationSeriesView view = service.createSeries(
                input(startsAt, startsAt.plusMinutes(15), 2), "alice");

        assertEquals(2, view.reservations().size(),
                "a 15 minute occurrence is exactly on the allowed lower bound and must be accepted");
        assertEquals(2, savedRows.size(),
                "both 15 minute occurrences must reach the persistence boundary, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofMinutes(15))),
                "control: the accepted occurrences must really have a 15 minute duration");
    }

    @Test
    void scn005EightHourOccurrenceIsAccepted() {
        OffsetDateTime startsAt = standardFutureStart();

        ReservationSeriesView view = service.createSeries(
                input(startsAt, startsAt.plusHours(8), 2), "alice");

        assertEquals(2, view.reservations().size(),
                "an 8 hour occurrence is exactly on the allowed upper bound and must be accepted");
        assertEquals(2, savedRows.size(),
                "both 8 hour occurrences must reach the persistence boundary, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofHours(8))),
                "control: the accepted occurrences must really have an 8 hour duration");
    }

    @Test
    void scn005FourteenMinuteOccurrenceIsRejectedAndCreatesNoRows() {
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

        // Nearby valid control: identical request, only the duration changes to exactly 15 minutes.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusMinutes(15), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same request at exactly 15 minutes must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must be saved, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofMinutes(15))),
                "valid control: the accepted rows must have the intended 15 minute duration");
    }

    @Test
    void scn005EightHourOneMinuteOccurrenceIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(
                        input(startsAt, startsAt.plusHours(8).plusMinutes(1), 2), "alice"),
                "an 8 hour 1 minute occurrence exceeds the 8 hour upper bound and must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "an over-long occurrence must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected 8 hour 1 minute series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: identical request, only the duration changes to exactly 8 hours.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(8), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same request at exactly 8 hours must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must be saved, but saw "
                        + savedRows.size());
        assertTrue(savedRows.stream().allMatch(row ->
                        Duration.between(row.getStartsAt(), row.getEndsAt()).equals(Duration.ofHours(8))),
                "valid control: the accepted rows must have the intended 8 hour duration");
    }

    // ---------------------------------------------------------------------
    // SCN-011#A1 - non-WEEKLY frequency and missing required field
    // ---------------------------------------------------------------------
    @Test
    void scn011NonWeeklyFrequencyIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();
        ReservationSeriesInput invalid = inputWithFrequency(startsAt, "DAILY");

        assertEquals("DAILY", invalid.frequency(),
                "control: the request must retain the supplied non-WEEKLY frequency");
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the non-WEEKLY request must remain valid");
        assertEquals(startsAt, invalid.startsAt(),
                "control: the startsAt of the non-WEEKLY request must remain valid and future");

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(invalid, "alice"),
                "a non-WEEKLY frequency must be rejected by request validation");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "a non-WEEKLY frequency must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected non-WEEKLY request must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: identical request, only the frequency changes to WEEKLY.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the unchanged payload with a WEEKLY frequency must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must reach persistence, but saw "
                        + savedRows.size());
    }

    @Test
    void scn011MissingRequiredFrequencyIsRejectedAndCreatesNoRows() {
        OffsetDateTime startsAt = standardFutureStart();
        ReservationSeriesInput invalid = inputWithFrequency(startsAt, null);

        assertNull(invalid.frequency(),
                "control: the request must really carry an omitted (null) required frequency");
        assertEquals(startsAt, invalid.startsAt(),
                "control: the startsAt of the missing-frequency request must remain valid and future");

        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(invalid, "alice"),
                "a payload missing the required frequency must be rejected by request validation");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "a missing required field must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected missing-field request must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // Nearby valid control: identical request, only the missing frequency is supplied.
        ReservationSeriesView control = service.createSeries(
                input(startsAt, startsAt.plusHours(1), 2), "alice");
        assertEquals(2, control.reservations().size(),
                "valid control: the same payload with the required frequency present must be accepted");
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must reach persistence, but saw "
                        + savedRows.size());
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
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt,
                endsAt, SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);

        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset and local time");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain the supplied offset and local time");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(SERIES_FREQUENCY, input.frequency(),
                "input control: the frequency must be WEEKLY");
        assertEquals(occurrences, input.occurrences().intValue(),
                "input control: the requested occurrence count must be retained");
        return input;
    }

    private ReservationSeriesInput inputWithFrequency(OffsetDateTime startsAt, String frequency) {
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt,
                startsAt.plusHours(1), SERIES_TIMEZONE, frequency, 2);

        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset and local time");
        assertEquals(startsAt.plusHours(1), input.endsAt(),
                "input control: endsAt must retain the supplied offset and local time");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(frequency, input.frequency(),
                "input control: the supplied frequency must be retained unmodified");
        assertEquals(2, input.occurrences().intValue(),
                "input control: the control occurrence count must be retained");
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
