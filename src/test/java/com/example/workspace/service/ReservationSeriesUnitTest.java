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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-layer obligations of SCN-006, SCN-007 and SCN-008 (job WBATCH-009).
 *
 * <p>Frozen assertions implemented here are exactly: {@code SCN-006#A1}, {@code SCN-006#A3},
 * {@code SCN-006#A4}, {@code SCN-007#A1} and {@code SCN-008#A1}. The persistence dimension
 * {@code SCN-006#A2} belongs to the database/integration layers and is not claimed here.</p>
 *
 * <p>Boundary: the production {@link ReservationService} is constructed directly with a fixed
 * {@link Clock} and Mockito repository doubles. No Spring context, no database, no HTTP. The
 * observation is the occurrence data the service expands and returns plus the booking writes the
 * service actually hands to {@link ReservationRepository}; the durable row state is out of scope for
 * this layer.</p>
 *
 * <p>Both daylight-saving fixtures use a 09:00 Europe/Berlin local start time, which exists exactly
 * once on every occurrence date of the fixture (the gap/overlap window is 02:00-03:00 local), so the
 * unresolved ambiguities AMB-001/AMB-002 about ambiguous or nonexistent local times are not
 * exercised.</p>
 */
class ReservationSeriesUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Before every fixture interval below; the series validation therefore sees future occurrences. */
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final Duration ONE_HOUR = Duration.ofMinutes(60);

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);

    /** Bookings the service handed to {@link ReservationRepository} during the current test. */
    private final List<Reservation> savedBookings = new ArrayList<>();

    private ReservationService service;
    private BookableResource room;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        savedBookings.clear();
        service = new ReservationService(resources, bookings, audits, FIXED_CLOCK);
        room = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM, "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(room, "id", 1L);
        when(resources.findLockedById(1L)).thenReturn(Optional.of(room));
        when(resources.findById(1L)).thenReturn(Optional.of(room));
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            savedBookings.add(saved);
            return saved;
        });
    }

    /**
     * SCN-006#A1 - across a daylight-saving transition every occurrence still starts at the same
     * local wall-clock time in the supplied zone, so occurrences are spaced by local calendar weeks
     * rather than by a fixed UTC offset.
     *
     * <p>Falsifiable in both directions: a fixed-offset implementation yields 10:00 local after the
     * spring-forward transition, and a "plus exactly 168 hours" implementation yields a shifted local
     * time. The expected UTC gaps therefore contain the single 167-hour week that crosses the
     * +01:00 to +02:00 change, while every occurrence still renders as 09:00 Europe/Berlin.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-006")
    @Tag("test_type:unit")
    @Tag("fault_model:dst_fixed_offset")
    void weeklyExpansionKeepsLocalWallClockTimeAcrossTheSpringForwardTransition() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);

        ReservationSeriesView result = service.createSeries(weeklySeries(firstStart, ONE_HOUR, 4), "alice");

        List<ReservationView> occurrences = orderedByStart(result.reservations());
        assertEquals(4, occurrences.size(),
                "all four requested occurrences must be expanded, found " + occurrences.size());
        assertEquals(firstStart.toInstant(), occurrences.get(0).startsAt(),
                "the first occurrence must start at the submitted instant");

        List<LocalTime> localTimes = occurrences.stream()
                .map(occurrence -> occurrence.startsAt().atZone(BERLIN).toLocalTime())
                .collect(Collectors.toList());
        assertEquals(Collections.nCopies(4, LocalTime.of(9, 0)), localTimes,
                "every occurrence must keep the 09:00 Europe/Berlin wall-clock time across the DST "
                        + "transition, found " + localTimes);

        List<ZoneOffset> offsets = occurrences.stream()
                .map(occurrence -> occurrence.startsAt().atZone(BERLIN).getOffset())
                .collect(Collectors.toList());
        assertEquals(List.of(ZoneOffset.ofHours(1), ZoneOffset.ofHours(1),
                        ZoneOffset.ofHours(2), ZoneOffset.ofHours(2)), offsets,
                "the fixture must straddle the +01:00 to +02:00 spring-forward change, found " + offsets);

        List<Duration> gaps = new ArrayList<>();
        for (int index = 1; index < occurrences.size(); index++) {
            gaps.add(Duration.between(occurrences.get(index - 1).startsAt(), occurrences.get(index).startsAt()));
        }
        assertEquals(List.of(Duration.ofHours(168), Duration.ofHours(167), Duration.ofHours(168)), gaps,
                "occurrences must be spaced by local calendar weeks (167 real hours across the spring-forward "
                        + "week) rather than by a fixed UTC offset, found " + gaps);
    }

    /**
     * SCN-006#A3 - a series whose occurrences all fall on dates where the requested local start time
     * exists uniquely is accepted, and every occurrence starts at that same local wall-clock time in
     * the supplied zone.
     *
     * <p>Uses the autumn fall-back transition (2030-10-27 03:00 CEST to 02:00 CET) rather than the
     * spring-forward fixture of {@code SCN-006#A1}: the offsets move in the other direction while
     * 09:00 local still exists exactly once on all four dates. Both the acceptance and every
     * occurrence's local wall-clock time are asserted, and the occurrence list is required to be
     * complete so the "every occurrence" check cannot pass on an empty or partial collection.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-006")
    @Tag("test_type:unit")
    @Tag("fault_model:dst_fixed_offset")
    void seriesOnDatesWithUniqueLocalStartTimeIsAcceptedAcrossTheFallBackTransition() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 10, 14, 9, 0, 0, 0, BERLIN);

        ReservationSeriesView result = assertDoesNotThrow(
                () -> service.createSeries(weeklySeries(firstStart, ONE_HOUR, 4), "alice"),
                "a series whose local start time exists uniquely on every occurrence date must be accepted");

        List<ReservationView> occurrences = orderedByStart(result.reservations());
        assertEquals(4, occurrences.size(),
                "an accepted four-occurrence series must expose all four occurrences, found " + occurrences.size());

        List<LocalDateTime> localStarts = occurrences.stream()
                .map(occurrence -> occurrence.startsAt().atZone(BERLIN).toLocalDateTime())
                .collect(Collectors.toList());
        List<LocalDateTime> expectedLocalStarts = IntStream.range(0, 4)
                .mapToObj(week -> firstStart.toLocalDateTime().plusWeeks(week))
                .collect(Collectors.toList());
        assertEquals(expectedLocalStarts, localStarts,
                "every occurrence must start at the same 09:00 local wall-clock time in Europe/Berlin, found "
                        + localStarts);

        List<ZoneOffset> offsets = occurrences.stream()
                .map(occurrence -> occurrence.startsAt().atZone(BERLIN).getOffset())
                .collect(Collectors.toList());
        assertEquals(List.of(ZoneOffset.ofHours(2), ZoneOffset.ofHours(2),
                        ZoneOffset.ofHours(1), ZoneOffset.ofHours(1)), offsets,
                "the fixture must straddle the +02:00 to +01:00 fall-back change, found " + offsets);
    }

    /**
     * SCN-006#A4 - expanding the series creates one booking for every requested occurrence date, so
     * no occurrence is dropped when the span crosses a daylight-saving transition.
     *
     * <p>Observed as the booking writes the service performs against
     * {@link ReservationRepository} on the same spring-forward fixture. Each requested local
     * occurrence date must be handed to the repository exactly once; a dropped occurrence shows up
     * as a missing date, a duplicated occurrence as a count of two.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-006")
    @Tag("test_type:unit")
    @Tag("fault_model:dropped_occurrence")
    void everyRequestedOccurrenceDateIsPersistedExactlyOnceAcrossTheSpringForwardTransition() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(firstStart, ONE_HOUR, 4), "alice");

        List<Reservation> writes = attemptedBookings();
        assertEquals(4, writes.size(),
                "expanding a four-occurrence series must create exactly one booking per requested occurrence, "
                        + "found " + writes.size());

        Map<LocalDate, Long> writesPerLocalDate = writes.stream().collect(Collectors.groupingBy(
                reservation -> reservation.getStartsAt().atZone(BERLIN).toLocalDate(),
                Collectors.counting()));
        assertEquals(Set.of(LocalDate.of(2030, 3, 18), LocalDate.of(2030, 3, 25),
                        LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 8)),
                writesPerLocalDate.keySet(),
                "every requested occurrence date must reach the repository, found " + writesPerLocalDate);
        writesPerLocalDate.forEach((date, count) -> assertEquals(1L, count.longValue(),
                "occurrence date " + date + " must be created exactly once, found " + writesPerLocalDate));
    }

    /**
     * SCN-007#A1, accepted lower bound - occurrences of exactly 15 minutes are accepted.
     *
     * <p>The nearby invalid control is
     * {@link #seriesWithFourteenMinuteOccurrencesIsRejectedWithoutCreatingBookings()}: the identical
     * request differs only in the submitted duration. Asserting the requested count and each
     * occurrence's 15-minute span proves the duration was really used instead of being silently
     * normalised.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-007")
    @Tag("test_type:unit")
    @Tag("input_partition_id:IP-012")
    void seriesWithExactlyFifteenMinuteOccurrencesIsAccepted() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        Duration fifteenMinutes = Duration.ofMinutes(15);

        ReservationSeriesView result = service.createSeries(weeklySeries(firstStart, fifteenMinutes, 3), "alice");

        List<ReservationView> occurrences = orderedByStart(result.reservations());
        assertEquals(3, occurrences.size(),
                "an accepted three-occurrence series must expose all three occurrences, found " + occurrences.size());
        for (ReservationView occurrence : occurrences) {
            assertEquals(fifteenMinutes, Duration.between(occurrence.startsAt(), occurrence.endsAt()),
                    "every accepted occurrence must keep its submitted 15-minute duration, found "
                            + Duration.between(occurrence.startsAt(), occurrence.endsAt()) + " at " + occurrence.startsAt());
        }
    }

    /**
     * SCN-007#A1, rejected lower bound - occurrences of 14 minutes are rejected and create no
     * bookings. Only the end instant differs from the accepted 15-minute control above.
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-007")
    @Tag("test_type:unit")
    @Tag("input_partition_id:IP-012")
    void seriesWithFourteenMinuteOccurrencesIsRejectedWithoutCreatingBookings() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        assertThrows(ApiException.class,
                () -> service.createSeries(weeklySeries(firstStart, Duration.ofMinutes(14), 3), "alice"),
                "occurrences shorter than the 15-minute lower bound must be rejected");

        assertTrue(attemptedBookings().isEmpty(),
                "a rejected 14-minute series must create no bookings, found " + attemptedBookings().size());
        verify(bookings, never()).save(any(Reservation.class));
    }

    /**
     * SCN-007#A1, accepted upper bound - occurrences of exactly 8 hours are accepted.
     *
     * <p>The nearby invalid control is
     * {@link #seriesWithEightHoursAndOneMinuteOccurrencesIsRejectedWithoutCreatingBookings()}: the
     * identical request differs only in the submitted duration.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-007")
    @Tag("test_type:unit")
    @Tag("input_partition_id:IP-013")
    void seriesWithExactlyEightHourOccurrencesIsAccepted() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        Duration eightHours = Duration.ofHours(8);

        ReservationSeriesView result = service.createSeries(weeklySeries(firstStart, eightHours, 3), "alice");

        List<ReservationView> occurrences = orderedByStart(result.reservations());
        assertEquals(3, occurrences.size(),
                "an accepted three-occurrence series must expose all three occurrences, found " + occurrences.size());
        for (ReservationView occurrence : occurrences) {
            assertEquals(eightHours, Duration.between(occurrence.startsAt(), occurrence.endsAt()),
                    "every accepted occurrence must keep its submitted 8-hour duration, found "
                            + Duration.between(occurrence.startsAt(), occurrence.endsAt()) + " at " + occurrence.startsAt());
        }
    }

    /**
     * SCN-007#A1, rejected upper bound - occurrences of 8 hours 1 minute are rejected and create no
     * bookings. Only the end instant differs from the accepted 8-hour control above.
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-007")
    @Tag("test_type:unit")
    @Tag("input_partition_id:IP-013")
    void seriesWithEightHoursAndOneMinuteOccurrencesIsRejectedWithoutCreatingBookings() {
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        assertThrows(ApiException.class,
                () -> service.createSeries(weeklySeries(firstStart, Duration.ofHours(8).plusMinutes(1), 3), "alice"),
                "occurrences longer than the 8-hour upper bound must be rejected");

        assertTrue(attemptedBookings().isEmpty(),
                "a rejected 8 hour 1 minute series must create no bookings, found " + attemptedBookings().size());
        verify(bookings, never()).save(any(Reservation.class));
    }

    /**
     * SCN-008#A1 - a series whose first occurrence start is in the past relative to the evaluated
     * clock is rejected and creates zero bookings.
     *
     * <p>Only the first start differs from the accepted control at the end of this test, which is
     * the same three-occurrence request moved after the fixed clock. The control proves the request
     * shape (occurrence count, duration, room) is valid, so the rejection is attributable to the
     * past start rather than to an unrelated validation guard. AMB-008 (how far in the past is
     * rejected) is not resolved by the oracle, so the fixture uses a start a week before the clock
     * instead of a boundary instant.</p>
     */
    @Test
    @Tag("observation_boundary:unit")
    @Tag("scenario:SCN-008")
    @Tag("test_type:unit")
    @Tag("input_partition_id:IP-015")
    void seriesWithPastFirstOccurrenceIsRejectedWithoutCreatingBookings() {
        ZonedDateTime pastStart = ZonedDateTime.of(2029, 12, 25, 9, 0, 0, 0, BERLIN);
        assertTrue(pastStart.toInstant().isBefore(FIXED_NOW),
                "fixture: the first occurrence must precede the fixed clock " + FIXED_NOW);

        assertThrows(ApiException.class,
                () -> service.createSeries(weeklySeries(pastStart, ONE_HOUR, 3), "alice"),
                "a series whose first occurrence starts before the evaluated clock must be rejected");

        assertTrue(attemptedBookings().isEmpty(),
                "a rejected past series must create zero bookings, found " + attemptedBookings().size());
        verify(bookings, never()).save(any(Reservation.class));

        ZonedDateTime futureStart = ZonedDateTime.of(2030, 5, 6, 9, 0, 0, 0, BERLIN);
        assertTrue(futureStart.toInstant().isAfter(FIXED_NOW),
                "fixture: the control series must start after the fixed clock " + FIXED_NOW);
        service.createSeries(weeklySeries(futureStart, ONE_HOUR, 3), "alice");
        assertEquals(3, attemptedBookings().size(),
                "control: the identical request moved into the future must be accepted and create three bookings, "
                        + "so the rejection above is caused by the past start");
    }

    private ReservationSeriesInput weeklySeries(ZonedDateTime firstStart, Duration duration, int occurrences) {
        ZonedDateTime firstEnd = firstStart.plus(duration);
        return new ReservationSeriesInput(1L, "Weekly sync",
                firstStart.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
    }

    private List<ReservationView> orderedByStart(List<ReservationView> reservations) {
        List<ReservationView> ordered = new ArrayList<>(reservations);
        ordered.sort((left, right) -> left.startsAt().compareTo(right.startsAt()));
        return ordered;
    }

    /**
     * Every {@link Reservation} the service handed to the reservation repository during this test.
     * The list is filled by the stubbed {@code save} answer, i.e. the observation is the actual
     * persistence attempt rather than the returned view.
     */
    private List<Reservation> attemptedBookings() {
        return new ArrayList<>(savedBookings);
    }
}
