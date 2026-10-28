package com.example.workspace.service;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.AuditEvent;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression verification that the pre-existing one-off reservation flow is unaffected by the
 * recurring-series feature (SCN-018, REQ-012, REQ-005).
 *
 * <p>The tests drive the documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)} - the same entry point the pre-existing
 * one-time suite exercises - against an active, free Room 101 and observe the returned
 * {@link ReservationView} response. Frozen expectation: the one-off booking is still created
 * successfully and the response carries {@code seriesId} with the value {@code null}, because a
 * one-off booking belongs to no series.</p>
 *
 * <p>The fixture slot is 2030-02-04 09:00-10:00Z, in the future relative to the fixed 2030-01-01
 * clock, and the fixture room is uniquely coded so no state is shared with other tests.</p>
 */
class OneOffReservationRegressionTest {

    private static final Long ROOM_101_ID = 101L;
    private static final Instant START = Instant.parse("2030-02-04T09:00:00Z");
    private static final Instant END = Instant.parse("2030-02-04T10:00:00Z");

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    private ReservationService service;
    private BookableResource room101;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        service = new ReservationService(resources, bookings, audits, clock);

        room101 = new BookableResource("R101", "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false);
        ReflectionTestUtils.setField(room101, "id", ROOM_101_ID);

        when(resources.findLockedById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(bookings.existsConflictingReservation(ROOM_101_ID, START, END)).thenReturn(false);
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * SCN-018#A1 - A valid one-off reservation is still created successfully after the series
     * feature ships: the single-reservation path returns the confirmed booking and persists it.
     */
    @Test
    void scn018A1ValidOneOffReservationIsStillCreatedSuccessfully() {
        ReservationView created = service.create(oneOffBooking(), "alice");

        assertNotNull(created, "the one-off creation path must still return the created reservation");
        assertEquals(ROOM_101_ID, created.resourceId(),
                "the created reservation must belong to the requested Room 101");
        assertEquals("alice", created.bookedBy(),
                "the created reservation must be owned by the requesting employee");
        assertEquals("One-off planning", created.title(),
                "the submitted one-off title must be preserved");
        assertEquals(START, created.startsAt(),
                "the created reservation must start at the requested slot");
        assertEquals(END, created.endsAt(),
                "the created reservation must end at the requested slot");
        assertEquals(ReservationStatus.CONFIRMED, created.status(),
                "a one-off booking against an active, free room without approval must be confirmed");

        verify(bookings).save(any(Reservation.class));
        verify(audits).save(any(AuditEvent.class));
    }

    /**
     * SCN-018#A3 - A one-off reservation response includes the seriesId attribute with the value
     * null, so no series identifier is reported for a one-off booking.
     */
    @Test
    void scn018A3OneOffReservationResponseReportsNullSeriesId() {
        ReservationView created = service.create(oneOffBooking(), "alice");

        assertNotNull(created, "the one-off creation path must still return the created reservation");
        assertNull(created.seriesId(),
                "a one-off reservation response must include seriesId with the value null, but was "
                        + created.seriesId());
    }

    private BookingInput oneOffBooking() {
        return new BookingInput(ROOM_101_ID, "One-off planning", START, END);
    }
}
