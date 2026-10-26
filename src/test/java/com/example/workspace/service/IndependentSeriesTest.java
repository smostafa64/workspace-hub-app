package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.*;
import com.example.workspace.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndependentSeriesTest {
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private ReservationService service;

    @BeforeEach void prepare() {
        service = new ReservationService(resources, reservations, audits,
                Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC));
        BookableResource room = new BookableResource("QA", "QA room", ResourceType.ROOM, "QA", 1, 5, true, false);
        ReflectionTestUtils.setField(room, "id", 1L);
        when(resources.findLockedById(1L)).thenReturn(Optional.of(room));
        when(reservations.save(any(Reservation.class))).thenAnswer(call -> call.getArgument(0));
    }

    private ReservationSeriesInput input(String start, String end, String zone, int count) {
        return new ReservationSeriesInput(1L, "Independent QA", OffsetDateTime.parse(start),
                OffsetDateTime.parse(end), zone, "WEEKLY", count);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void everySupportedCountHasStableMembershipAndExactWeeklyInstants(int count) {
        ReservationSeriesView result = service.createSeries(input("2030-01-06T10:00Z", "2030-01-06T11:00Z", "UTC", count), "alice");
        assertNotNull(result.seriesId());
        assertEquals(count, result.occurrences());
        assertEquals(count, result.reservations().size());
        IntStream.range(0, count).forEach(index -> {
            ReservationView occurrence = result.reservations().get(index);
            assertEquals(result.seriesId(), occurrence.seriesId());
            assertEquals(1L, occurrence.resourceId());
            assertEquals("Independent QA", occurrence.title());
            assertEquals("alice", occurrence.bookedBy());
            assertEquals(ReservationStatus.CONFIRMED, occurrence.status());
            assertEquals(Instant.parse("2030-01-06T10:00:00Z").plus(Duration.ofDays(7L * index)), occurrence.startsAt());
            assertEquals(Duration.ofHours(1), Duration.between(occurrence.startsAt(), occurrence.endsAt()));
        });
        verify(audits, times(count)).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "Europe/Berlin,2030-03-24T09:00+01:00,2030-03-24T10:00+01:00,+01:00,+02:00",
            "Europe/Berlin,2030-10-20T09:00+02:00,2030-10-20T10:00+02:00,+02:00,+01:00",
            "America/New_York,2030-03-03T09:00-05:00,2030-03-03T10:00-05:00,-05:00,-04:00",
            "Asia/Tehran,2030-03-24T09:00+03:30,2030-03-24T10:00+03:30,+03:30,+03:30",
            "Australia/Lord_Howe,2030-03-31T09:00+11:00,2030-03-31T10:00+11:00,+11:00,+10:30"
    })
    void weeklyStartAndEndKeepLocalClockAcrossOffsetChanges(String zone, String start, String end, String before, String after) {
        ReservationSeriesView result = service.createSeries(input(start, end, zone, 3), "alice");
        ZoneId timezone = ZoneId.of(zone);
        assertEquals(ZoneOffset.of(before), result.reservations().getFirst().startsAt().atZone(timezone).getOffset());
        assertEquals(ZoneOffset.of(after), result.reservations().get(1).startsAt().atZone(timezone).getOffset());
        for (int index = 0; index < 3; index++) {
            ReservationView occurrence = result.reservations().get(index);
            assertEquals(OffsetDateTime.parse(start).toLocalDateTime().plusWeeks(index), occurrence.startsAt().atZone(timezone).toLocalDateTime());
            assertEquals(OffsetDateTime.parse(end).toLocalDateTime().plusWeeks(index), occurrence.endsAt().atZone(timezone).toLocalDateTime());
        }
    }

    @Test void overlapChoosesEarlierInstantForBothEndpoints() {
        ReservationSeriesView result = service.createSeries(input("2030-10-20T02:15+02:00", "2030-10-20T02:45+02:00", "Europe/Berlin", 3), "alice");
        assertEquals(Instant.parse("2030-10-27T00:15:00Z"), result.reservations().get(1).startsAt());
        assertEquals(Instant.parse("2030-10-27T00:45:00Z"), result.reservations().get(1).endsAt());
        assertEquals(Instant.parse("2030-11-03T01:15:00Z"), result.reservations().get(2).startsAt());
    }

    @Test void firstGapInputUsesPreTransitionOffsetAndDoesNotShiftLaterWeeks() {
        ReservationSeriesView result = service.createSeries(input("2030-03-31T02:15+01:00", "2030-03-31T02:45+01:00", "Europe/Berlin", 3), "alice");
        assertEquals(Instant.parse("2030-03-31T01:15:00Z"), result.reservations().getFirst().startsAt());
        assertEquals(Instant.parse("2030-03-31T01:45:00Z"), result.reservations().getFirst().endsAt());
        assertEquals(Instant.parse("2030-04-07T00:15:00Z"), result.reservations().get(1).startsAt());
    }

    @Test void laterGapOccurrenceShiftsOnlyThatWeek() {
        ReservationSeriesView result = service.createSeries(input("2030-03-24T02:15+01:00", "2030-03-24T02:45+01:00", "Europe/Berlin", 3), "alice");
        assertEquals(Instant.parse("2030-03-31T01:15:00Z"), result.reservations().get(1).startsAt());
        assertEquals(Instant.parse("2030-04-07T00:15:00Z"), result.reservations().get(2).startsAt());
    }

    @ParameterizedTest
    @CsvSource({
            "2030-03-24T01:55+01:00,2030-03-24T03:05+01:00",
            "2030-10-20T00:30+02:00,2030-10-20T08:00+02:00",
            "2030-03-24T02:30+01:00,2030-03-24T03:15+01:00"
    })
    void invalidElapsedDurationInLaterOccurrenceRejectsEntireSeries(String start, String end) {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(input(start, end, "Europe/Berlin", 3), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        assertTrue(failure.getMessage().contains("Occurrence 2"), failure.getMessage());
        verify(reservations, never()).save(any());
        verifyNoInteractions(audits);
    }

    @ParameterizedTest
    @CsvSource({
            "2030-10-27T02:15+01:00,2030-10-27T02:45+01:00",
            "2030-03-24T10:00+02:00,2030-03-24T11:00+02:00",
            "2030-03-31T02:15+02:00,2030-03-31T02:45+02:00"
    })
    void explicitOffsetsInconsistentWithZonePolicyAreRejected(String start, String end) {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.createSeries(input(start, end, "Europe/Berlin", 3), "alice")).status());
        verifyNoInteractions(reservations, audits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+01:00", "GMT+01:00"})
    void timezoneMustBeAnIanaIdentifier(String zone) {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.createSeries(input("2030-01-06T10:00+01:00", "2030-01-06T11:00+01:00", zone, 3), "alice")).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void conflictIndexesAreOneBasedAndNothingIsWritten(int conflictIndex) {
        Instant start = Instant.parse("2030-01-06T10:00:00Z").plus(Duration.ofDays(7L * (conflictIndex - 1)));
        when(reservations.existsConflictingReservation(1L, start, start.plusSeconds(3600))).thenReturn(true);
        ReservationConflictException failure = assertThrows(ReservationConflictException.class,
                () -> service.createSeries(input("2030-01-06T10:00Z", "2030-01-06T11:00Z", "UTC", 3), "alice"));
        assertEquals(List.of(conflictIndex), failure.getConflicts().stream().map(ReservationConflictItem::occurrenceIndex).toList());
        assertEquals(start, failure.getConflicts().getFirst().startsAt().toInstant());
        verify(reservations, never()).save(any());
        verifyNoInteractions(audits);
    }
}
