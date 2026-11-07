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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Unit-layer verification for the reservation-series input validation of the timezone,
 * frequency and past first occurrence.
 *
 * <p>Every test calls the real {@link ReservationService} in process. The repository interfaces
 * are the only mocked collaborators because they are the persistence boundary, not the logic
 * under test. The service's blank/format validation, IANA zone parsing, occurrence expansion,
 * future-occurrence validation and the ordering that rejects before any write all execute as
 * production code. No business rule is reimplemented in this test.</p>
 *
 * <p>The injected {@link Clock} is fixed at {@link #FIXED_NOW}, which is the only definition of
 * "now" used by the occurrence validation. The timestamps are derived from that injected clock,
 * not from the real wall clock, so the negative partitions remain deterministic and the future
 * control stays genuinely future relative to the clock that the service observes.</p>
 *
 * <p>Each negative test first proves the constructed request retains the intended invalid value,
 * then asserts the request is rejected with a client error and that no reservation row reached
 * the persistence boundary. A nearby valid control with the identical request except for the one
 * invalid property is then accepted, proving the rejection is attributable to that property and
 * not to an unrelated validation guard.</p>
 */
class ReservationSeriesInputValidationUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** The fixed injected clock; every past/future assertion is evaluated against this instant. */
    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T10:00:00Z");

    /** A future, zone-consistent local start derived from the injected clock (never from real now). */
    private static final OffsetDateTime FUTURE_START = ZonedDateTime.ofInstant(FIXED_NOW, BERLIN)
            .plusWeeks(1)
            .withHour(9)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toOffsetDateTime();

    private static final long RESOURCE_ID = 1L;
    private static final String TITLE = "QA series input validation";

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
                "QA validation room", ResourceType.ROOM, "HQ", 2, 10, true, false);
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
    // SCN-006#A1 - invalid timezone values
    // ---------------------------------------------------------------------
    @Test
    void scn006NonIanaTimezoneIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "Mars/Phobos", "WEEKLY", 2);

        assertEquals("Mars/Phobos", invalid.timezone(),
                "control: the request must retain the supplied non-IANA timezone");
        assertEquals("WEEKLY", invalid.frequency(),
                "control: the frequency of the non-IANA request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the non-IANA request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    @Test
    void scn006EmptyTimezoneIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "", "WEEKLY", 2);

        assertEquals("", invalid.timezone(),
                "control: the request must retain the supplied empty timezone value");
        assertEquals("WEEKLY", invalid.frequency(),
                "control: the frequency of the empty-timezone request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the empty-timezone request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    @Test
    void scn006BlankTimezoneIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "   ", "WEEKLY", 2);

        assertEquals("   ", invalid.timezone(),
                "control: the request must retain the supplied blank timezone value");
        assertEquals("WEEKLY", invalid.frequency(),
                "control: the frequency of the blank-timezone request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the blank-timezone request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    // ---------------------------------------------------------------------
    // SCN-008#A1 - frequencies other than WEEKLY
    // ---------------------------------------------------------------------
    @Test
    void scn008DailyFrequencyIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "Europe/Berlin", "DAILY", 2);

        assertEquals("DAILY", invalid.frequency(),
                "control: the request must retain the supplied DAILY frequency");
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the DAILY request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the DAILY request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    @Test
    void scn008MonthlyFrequencyIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "Europe/Berlin", "MONTHLY", 2);

        assertEquals("MONTHLY", invalid.frequency(),
                "control: the request must retain the supplied MONTHLY frequency");
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the MONTHLY request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the MONTHLY request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    @Test
    void scn008AbsentFrequencyIsRejectedAndCreatesNoRows() {
        ReservationSeriesInput invalid = input(FUTURE_START, FUTURE_START.plusHours(1),
                "Europe/Berlin", null, 2);

        assertNull(invalid.frequency(),
                "control: the request must really carry an absent (null) frequency");
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the absent-frequency request must remain valid");
        assertEquals(FUTURE_START, invalid.startsAt(),
                "control: the startsAt of the absent-frequency request must remain valid and future");

        assertRejectedWithoutRows(invalid);
        assertValidControlAccepted();
    }

    // ---------------------------------------------------------------------
    // SCN-009#A1 - first occurrence that is not in the future
    // ---------------------------------------------------------------------
    @Test
    void scn009StartOneHourBeforeInjectedNowIsRejectedAndCreatesNoRows() {
        OffsetDateTime pastStart = OffsetDateTime.ofInstant(
                FIXED_NOW.minus(Duration.ofHours(1)), BERLIN);
        ReservationSeriesInput invalid = input(pastStart, pastStart.plusHours(1),
                "Europe/Berlin", "WEEKLY", 2);

        assertEquals(FIXED_NOW.minus(Duration.ofHours(1)), invalid.startsAt().toInstant(),
                "control: the request must carry the intended start one hour before the injected clock");
        assertTrue(invalid.startsAt().toInstant().isBefore(FIXED_NOW),
                "control: the supplied start must really be in the past relative to the injected clock");
        assertZoneConsistent(invalid.startsAt());
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the past-start request must remain valid");
        assertEquals("WEEKLY", invalid.frequency(),
                "control: the frequency of the past-start request must remain valid");
        assertEquals(pastStart.plusHours(1), invalid.endsAt(),
                "control: endsAt must remain after startsAt so only the future rule can reject");

        assertRejectedWithoutRows(invalid);
        assertAcceptedWhenClockPrecedesStart(invalid);
    }

    @Test
    void scn009StartOneMinuteBeforeInjectedNowIsRejectedAndCreatesNoRows() {
        OffsetDateTime pastStart = OffsetDateTime.ofInstant(
                FIXED_NOW.minus(Duration.ofMinutes(1)), BERLIN);
        ReservationSeriesInput invalid = input(pastStart, pastStart.plusHours(1),
                "Europe/Berlin", "WEEKLY", 2);

        assertEquals(FIXED_NOW.minus(Duration.ofMinutes(1)), invalid.startsAt().toInstant(),
                "control: the request must carry the intended start one minute before the injected clock");
        assertTrue(invalid.startsAt().toInstant().isBefore(FIXED_NOW),
                "control: the supplied start must really be in the past relative to the injected clock");
        assertZoneConsistent(invalid.startsAt());
        assertEquals("Europe/Berlin", invalid.timezone(),
                "control: the timezone of the past-start request must remain valid");
        assertEquals("WEEKLY", invalid.frequency(),
                "control: the frequency of the past-start request must remain valid");
        assertEquals(pastStart.plusHours(1), invalid.endsAt(),
                "control: endsAt must remain after startsAt so only the future rule can reject");

        assertRejectedWithoutRows(invalid);
        assertAcceptedWhenClockPrecedesStart(invalid);
    }

    @Test
    void scn009StartExactlyAtInjectedNowIsRejectedAndCreatesNoRows() {
        OffsetDateTime nowStart = OffsetDateTime.ofInstant(FIXED_NOW, BERLIN);
        ReservationSeriesInput invalid = input(nowStart, nowStart.plusHours(1),
                "Europe/Berlin", "WEEKLY", 2);

        assertEquals(FIXED_NOW, invalid.startsAt().toInstant(),
                "control: the request must carry a start exactly at the injected clock instant");
        assertTrue(!invalid.startsAt().toInstant().isAfter(FIXED_NOW),
                "control: a start equal to the injected clock instant is not in the future");
        assertZoneConsistent(invalid.startsAt());

        assertRejectedWithoutRows(invalid);
        assertAcceptedWhenClockPrecedesStart(invalid);
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------

    private void assertRejectedWithoutRows(ReservationSeriesInput request) {
        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(request, "alice"),
                "the invalid series request must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "the invalid series request must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected series request must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));
    }

    private void assertValidControlAccepted() {
        ReservationSeriesView control = service.createSeries(
                input(FUTURE_START, FUTURE_START.plusHours(1), "Europe/Berlin", "WEEKLY", 2),
                "alice");

        assertEquals(2, control.occurrences(),
                "valid control: the same request with only the invalid property corrected must be accepted");
        assertEquals(2, control.reservations().size(),
                "valid control: the accepted series must expose two reservations, but saw "
                        + control.reservations().size());
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must reach the persistence boundary");
    }

    /**
     * Nearby valid control for the future-occurrence rule: the identical request is submitted to
     * a service whose injected clock precedes the first occurrence, so only the temporal
     * relationship under test changes. The request itself is proven valid by its acceptance.
     */
    private void assertAcceptedWhenClockPrecedesStart(ReservationSeriesInput request) {
        Instant controlClock = request.startsAt().toInstant().minus(Duration.ofDays(1));
        ReservationService controlService = new ReservationService(resources, bookings, audits,
                Clock.fixed(controlClock, ZoneOffset.UTC));

        ReservationSeriesView control = controlService.createSeries(request, "alice");

        assertEquals(2, control.occurrences(),
                "valid control: the identical request must be accepted once the injected clock "
                        + "precedes its start, proving only the future rule rejected it");
        assertEquals(2, control.reservations().size(),
                "valid control: the accepted series must expose two reservations, but saw "
                        + control.reservations().size());
        assertEquals(2, savedRows.size(),
                "valid control: exactly the two control occurrences must reach the persistence boundary");
    }

    /**
     * Proves the supplied timestamp maps back to the same instant in the series zone, so the
     * timezone-consistency policy cannot be the reason a past occurrence is rejected.
     */
    private static void assertZoneConsistent(OffsetDateTime value) {
        assertEquals(value.toInstant(),
                ZonedDateTime.of(value.toLocalDateTime(), BERLIN).toInstant(),
                "control: the supplied timestamp must round-trip through the series zone so only "
                        + "the future-occurrence rule can reject the request");
    }

    private ReservationSeriesInput input(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                        String timezone, String frequency, int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt, endsAt,
                timezone, frequency, occurrences);

        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset and local time");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain the supplied offset and local time");
        assertEquals(timezone, input.timezone(),
                "input control: the supplied timezone must be retained unmodified");
        assertEquals(frequency, input.frequency(),
                "input control: the supplied frequency must be retained unmodified");
        assertEquals(occurrences, input.occurrences().intValue(),
                "input control: the requested occurrence count must be retained");
        return input;
    }
}
