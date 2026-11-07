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
 * Unit-layer verification of the frozen SCN-012#A1 obligation: a series whose first occurrence
 * starts earlier than the request instant is rejected, while the identical payload whose first
 * occurrence starts in the future is accepted.
 *
 * <p>Every test executes the real {@link ReservationService#createSeries} in process. Only the
 * repository interfaces (the persistence boundary) are mocked; the future-occurrence validation
 * gate, the weekly expansion and the reject-before-write ordering are production code. The
 * repository mock records every {@link Reservation} the service asks to persist, so the
 * "rejected request writes nothing" and "accepted control writes its rows" observations come from
 * the service's real save calls rather than from the returned view size.</p>
 *
 * <p>"Now" is the fixed injected {@link Clock}; the past and future starts are derived from that
 * same instant, never from the real wall clock. The two requests are identical in resource,
 * title, timezone, frequency, occurrence count and occurrence duration, and differ only in the
 * temporal position of the series, so a rejection cannot be attributed to an unrelated guard.</p>
 */
class ReservationSeriesPastStartUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final long RESOURCE_ID = 1L;
    private static final String TITLE = "QA SCN-012 past start";
    private static final int OCCURRENCES = 3;
    private static final Duration OCCURRENCE_LENGTH = Duration.ofHours(1);

    /** The single definition of "the request instant" observed by the service under test. */
    private static final Instant INJECTED_NOW = Instant.parse("2026-06-15T10:00:00Z");

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
                Clock.fixed(INJECTED_NOW, ZoneOffset.UTC));

        BookableResource resource = new BookableResource(
                "QA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "QA SCN-012 active room", ResourceType.ROOM, "HQ", 2, 10, true, false);
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
    // SCN-012#A1 - a past start is rejected while the identical future start is accepted
    // ---------------------------------------------------------------------
    @Test
    void scn012PastStartIsRejectedWhileIdenticalFutureStartIsAccepted() {
        // The past start is one hour before the injected request instant; the future control is
        // the same local wall-clock start two weeks later, so only the temporal position changes.
        ZonedDateTime pastLocal = ZonedDateTime.ofInstant(INJECTED_NOW, BERLIN)
                .minus(OCCURRENCE_LENGTH);
        OffsetDateTime pastStart = pastLocal.toOffsetDateTime();
        OffsetDateTime futureStart = pastLocal.plusWeeks(2).toOffsetDateTime();

        OffsetDateTime pastEnd = pastStart.plus(OCCURRENCE_LENGTH);
        OffsetDateTime futureEnd = futureStart.plus(OCCURRENCE_LENGTH);

        // Controls: the two payloads differ only in the position of the start, and each really
        // sits on the intended side of the injected request instant.
        assertTrue(pastStart.toInstant().isBefore(INJECTED_NOW),
                "control: the rejected first occurrence must really start before the request instant");
        assertTrue(futureStart.toInstant().isAfter(INJECTED_NOW),
                "control: the accepted first occurrence must really start after the request instant");
        assertEquals(pastStart.toLocalTime(), futureStart.toLocalTime(),
                "control: both requests must carry the same local wall-clock start time");
        assertEquals(Duration.between(pastStart, pastEnd), Duration.between(futureStart, futureEnd),
                "control: both requests must carry the same occurrence duration");
        assertEquals(futureStart.toInstant(), pastStart.toInstant().plus(Duration.ofDays(14)),
                "control: the future control must be exactly the same series advanced two weeks");

        ReservationSeriesInput pastRequest = input(pastStart, pastEnd);
        ReservationSeriesInput futureRequest = input(futureStart, futureEnd);

        // The identical fields prove the two inputs differ only in the temporal position.
        assertEquals(SERIES_TIMEZONE, futureRequest.timezone(),
                "control: both requests must carry the same timezone");
        assertEquals(SERIES_FREQUENCY, futureRequest.frequency(),
                "control: both requests must carry the same frequency");
        assertEquals(OCCURRENCES, futureRequest.occurrences().intValue(),
                "control: both requests must request the same number of occurrences");

        // The past-start series must be rejected as a client error and must write nothing.
        ApiException rejection = assertThrows(ApiException.class,
                () -> service.createSeries(pastRequest, "alice"),
                "a series starting before the request instant must be rejected");

        assertEquals(HttpStatus.BAD_REQUEST, rejection.status(),
                "a past first occurrence must be rejected as a client error, but saw "
                        + rejection.status());
        assertTrue(savedRows.isEmpty(),
                "the rejected past-start series must create no reservation rows, but saw "
                        + savedRows.size());
        verify(bookings, never()).save(any(Reservation.class));

        // The identical payload with a future start must be accepted by the same service and
        // clock, proving the rejection is attributable to the past start and not to any other
        // property of the request.
        ReservationSeriesView accepted = service.createSeries(futureRequest, "alice");

        assertEquals(OCCURRENCES, accepted.occurrences(),
                "valid control: the identical request with a future start must be accepted");
        assertEquals(Long.valueOf(RESOURCE_ID), accepted.resourceId(),
                "valid control: the accepted series must target the same resource");
        assertEquals(TITLE, accepted.title(),
                "valid control: the accepted series must carry the same title");
        assertEquals(OCCURRENCES, accepted.reservations().size(),
                "valid control: the accepted series must expose all requested occurrences, but saw "
                        + accepted.reservations().size());
        assertEquals(OCCURRENCES, savedRows.size(),
                "valid control: exactly the accepted occurrences must reach the persistence "
                        + "boundary, but saw " + savedRows.size());
        assertEquals(futureStart.toInstant(), savedRows.get(0).getStartsAt(),
                "valid control: the first accepted occurrence must start at the future start, but "
                        + "saw " + savedRows.get(0).getStartsAt());
        assertTrue(savedRows.stream().allMatch(row -> row.getStartsAt().isAfter(INJECTED_NOW)),
                "valid control: every accepted occurrence must start after the request instant");
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------

    private ReservationSeriesInput input(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        ReservationSeriesInput input = new ReservationSeriesInput(RESOURCE_ID, TITLE, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, OCCURRENCES);

        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain the supplied offset and local time");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain the supplied offset and local time");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(SERIES_FREQUENCY, input.frequency(),
                "input control: the frequency must be WEEKLY");
        assertEquals(OCCURRENCES, input.occurrences().intValue(),
                "input control: the requested occurrence count must be retained");
        return input;
    }
}
