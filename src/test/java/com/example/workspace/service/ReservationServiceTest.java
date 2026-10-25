package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.*;
import com.example.workspace.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationServiceTest {
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private ReservationService service;
    private BookableResource alpha;
    private final Instant start = Instant.parse("2030-01-02T10:00:00Z");
    private final Instant end = Instant.parse("2030-01-02T11:00:00Z");

    @BeforeEach void setup() {
        reset(resources, bookings, audits);
        service = new ReservationService(resources, bookings, audits, clock);
        alpha = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM,
                "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(alpha, "id", 1L);
        when(resources.findLockedById(1L)).thenReturn(Optional.of(alpha));
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test void successfulReservationIsConfirmedAndAudited() {
        ReservationView result = service.create(input(start, end), "alice");
        assertEquals(ReservationStatus.CONFIRMED, result.status());
        assertEquals("alice", result.bookedBy());
        verify(bookings).save(any(Reservation.class));
        verify(audits).save(any(AuditEvent.class));
    }

    @Test void approvalRequiredResourceProducesPendingReservation() {
        alpha.update("Conference Alpha", ResourceType.ROOM, "HQ", 2, 10, true, true);
        assertEquals(ReservationStatus.PENDING, service.create(input(start, end), "alice").status());
    }

    @Test void overlappingBookingIsRejected() {
        when(bookings.existsConflictingReservation(1L, start, end)).thenReturn(true);
        ApiException error = assertThrows(ApiException.class, () -> service.create(input(start, end), "bob"));
        assertEquals(HttpStatus.CONFLICT, error.status());
        verify(bookings, never()).save(any(Reservation.class));
    }

    @Test void bookingOnDisabledResourceIsRejected() {
        alpha.update("Conference Alpha", ResourceType.ROOM, "HQ", 2, 10, false, false);
        ApiException error = assertThrows(ApiException.class, () -> service.create(input(start, end), "alice"));
        assertEquals(HttpStatus.CONFLICT, error.status());
    }

    @Test void pastBookingIsRejectedWithoutDatabaseWrite() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(input(start.minus(Duration.ofDays(3)), end), "alice"));
        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        verifyNoInteractions(bookings);
    }

    @Test void shorterThanFifteenMinutesIsRejected() {
        assertThrows(ApiException.class,
                () -> service.create(input(start, start.plus(Duration.ofMinutes(14))), "alice"));
    }

    @Test void moreThanEightHoursIsRejected() {
        assertThrows(ApiException.class,
                () -> service.create(input(start, start.plus(Duration.ofHours(9))), "alice"));
    }

    @Test void bookingCannotBeCancelledByAnotherEmployee() {
        Reservation reservation = reservation(ReservationStatus.CONFIRMED);
        when(bookings.findById(99L)).thenReturn(Optional.of(reservation));
        ApiException error = assertThrows(ApiException.class,
                () -> service.cancel(99L, new UsernamePasswordAuthenticationToken("bob", "", List.of())));
        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    @Test void ownerCanCancelReservation() {
        Reservation reservation = reservation(ReservationStatus.CONFIRMED);
        when(bookings.findById(99L)).thenReturn(Optional.of(reservation));
        ReservationView result = service.cancel(99L, new UsernamePasswordAuthenticationToken("alice", "", List.of()));
        assertEquals(ReservationStatus.CANCELLED, result.status());
        verify(audits).save(any(AuditEvent.class));
    }

    @Test void managerDecisionApprovesOnlyPendingReservation() {
        Reservation reservation = reservation(ReservationStatus.PENDING);
        when(bookings.findById(99L)).thenReturn(Optional.of(reservation));
        assertEquals(ReservationStatus.CONFIRMED, service.decide(99L, true, "manager").status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.decide(99L, true, "manager")).status());
    }

    private BookingInput input(Instant from, Instant to) { return new BookingInput(1L, "Planning", from, to); }
    private Reservation reservation(ReservationStatus status) {
        Reservation reservation = new Reservation(alpha, "alice", "Planning", start, end, status, clock.instant());
        ReflectionTestUtils.setField(reservation, "id", 99L);
        return reservation;
    }
}
