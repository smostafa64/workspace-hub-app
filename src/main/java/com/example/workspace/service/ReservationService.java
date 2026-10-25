package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.*;
import com.example.workspace.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service
public class ReservationService {
    private final ResourceRepository resources;
    private final ReservationRepository bookings;
    private final AuditRepository audits;
    private final Clock clock;
    public ReservationService(ResourceRepository resources, ReservationRepository bookings,
                              AuditRepository audits, Clock clock) {
        this.resources = resources; this.bookings = bookings; this.audits = audits; this.clock = clock;
    }

    @Transactional
    public ReservationView create(BookingInput input, String actor) {
        validateTimes(input.startsAt(), input.endsAt());
        // Lock the resource row before checking and saving; serializes competing bookings for this resource.
        BookableResource resource = resources.findLockedById(input.resourceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Resource not found"));
        if (!resource.isActive()) throw new ApiException(HttpStatus.CONFLICT, "Resource is unavailable");
        if (bookings.existsConflictingReservation(resource.getId(), input.startsAt(), input.endsAt()))
            throw new ApiException(HttpStatus.CONFLICT, "Time slot conflicts with an existing reservation");
        ReservationStatus status = resource.isApprovalRequired() ? ReservationStatus.PENDING : ReservationStatus.CONFIRMED;
        Reservation reservation = bookings.save(new Reservation(resource, actor, input.title().trim(),
                input.startsAt(), input.endsAt(), status, clock.instant()));
        audits.save(new AuditEvent(actor, "BOOKED", "reservation:" + reservation.getId(), clock.instant()));
        return ReservationView.of(reservation);
    }

    @Transactional(readOnly = true)
    public boolean isAvailable(Long resourceId, Instant start, Instant end) {
        validateTimes(start, end);
        BookableResource resource = resources.findById(resourceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Resource not found"));
        return resource.isActive() && !bookings.existsConflictingReservation(resourceId, start, end);
    }

    @Transactional(readOnly = true)
    public List<ReservationView> list(Authentication auth, boolean all) {
        boolean privileged = hasAnyRole(auth, "ROLE_MANAGER", "ROLE_ADMIN");
        List<Reservation> list = all && privileged ? bookings.findAllByOrderByStartsAtDesc()
                : bookings.findByBookedByOrderByStartsAtDesc(auth.getName());
        return list.stream().map(ReservationView::of).toList();
    }

    @Transactional
    public ReservationView cancel(Long id, Authentication auth) {
        Reservation reservation = find(id);
        if (!reservation.getBookedBy().equals(auth.getName()) && !hasAnyRole(auth, "ROLE_MANAGER", "ROLE_ADMIN"))
            throw new ApiException(HttpStatus.FORBIDDEN, "Cannot cancel someone else's booking");
        if (reservation.getStatus() == ReservationStatus.CANCELLED || reservation.getStatus() == ReservationStatus.REJECTED)
            throw new ApiException(HttpStatus.CONFLICT, "Reservation is already closed");
        reservation.setStatus(ReservationStatus.CANCELLED);
        audits.save(new AuditEvent(auth.getName(), "CANCELLED", "reservation:" + id, clock.instant()));
        return ReservationView.of(reservation);
    }

    @Transactional
    public ReservationView decide(Long id, boolean approved, String actor) {
        Reservation reservation = find(id);
        if (reservation.getStatus() != ReservationStatus.PENDING)
            throw new ApiException(HttpStatus.CONFLICT, "Only pending reservations can be reviewed");
        reservation.setStatus(approved ? ReservationStatus.CONFIRMED : ReservationStatus.REJECTED);
        audits.save(new AuditEvent(actor, approved ? "APPROVED" : "REJECTED",
                "reservation:" + id, clock.instant()));
        return ReservationView.of(reservation);
    }

    private Reservation find(Long id) {
        return bookings.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }
    private void validateTimes(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start must be before end");
        if (!start.isAfter(clock.instant()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Reservation must start in the future");
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 15 || Duration.between(start, end).compareTo(Duration.ofHours(8)) > 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Booking must last between 15 minutes and 8 hours");
    }
    private boolean hasAnyRole(Authentication auth, String... roles) {
        return auth.getAuthorities().stream().anyMatch(a -> java.util.Arrays.asList(roles).contains(a.getAuthority()));
    }
}
