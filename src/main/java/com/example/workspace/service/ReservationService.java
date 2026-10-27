package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.*;
import com.example.workspace.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Transactional
    public ReservationSeriesView createSeries(ReservationSeriesInput input, String actor) {
        validateSeriesInput(input);
        ZoneId zone = ZoneId.of(input.timezone().trim());
        validateDateTimeMatchesZonePolicy(input.startsAt(), zone, "startsAt");
        validateDateTimeMatchesZonePolicy(input.endsAt(), zone, "endsAt");

        BookableResource resource = resources.findLockedById(input.resourceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Resource not found"));
        if (!resource.isActive()) throw new ApiException(HttpStatus.CONFLICT, "Resource is unavailable");

        List<OccurrencePlan> occurrences = expandSeries(input, zone);
        List<ReservationConflictItem> conflicts = new ArrayList<>();
        for (OccurrencePlan occurrence : occurrences) {
            validateOccurrence(occurrence);
            if (bookings.existsConflictingReservation(resource.getId(), occurrence.startInstant(), occurrence.endInstant())) {
                conflicts.add(new ReservationConflictItem(occurrence.index(), occurrence.startOffset(), occurrence.endOffset()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ReservationConflictException(conflicts);
        }

        UUID seriesId = UUID.randomUUID();
        List<Reservation> saved = new ArrayList<>();
        for (OccurrencePlan occurrence : occurrences) {
            ReservationStatus status = resource.isApprovalRequired() ? ReservationStatus.PENDING : ReservationStatus.CONFIRMED;
            Reservation reservation = bookings.save(new Reservation(resource, actor, input.title().trim(),
                    occurrence.startInstant(), occurrence.endInstant(), status, clock.instant(), seriesId));
            audits.save(new AuditEvent(actor, "BOOKED", "reservation:" + reservation.getId(), clock.instant()));
            saved.add(reservation);
        }

        return new ReservationSeriesView(seriesId, resource.getId(), input.title().trim(), zone.getId(),
                input.frequency().trim(), occurrences.size(), saved.stream().map(ReservationView::of).toList());
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

    private void validateSeriesInput(ReservationSeriesInput input) {
        if (input == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Request body is required");
        if (input.resourceId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "resourceId is required");
        if (input.title() == null || input.title().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "title is required");
        if (input.startsAt() == null || input.endsAt() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "startsAt and endsAt are required");
        if (!input.startsAt().isBefore(input.endsAt()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "endsAt must be after startsAt");
        if (input.timezone() == null || input.timezone().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "timezone is required");
        if (input.frequency() == null || !"WEEKLY".equalsIgnoreCase(input.frequency().trim()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "frequency must be WEEKLY");
        if (input.occurrences() == null || input.occurrences() < 2 || input.occurrences() > 12)
            throw new ApiException(HttpStatus.BAD_REQUEST, "occurrences must be between 2 and 12");
        try {
            ZoneId.of(input.timezone().trim());
        } catch (DateTimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "timezone must be a valid IANA timezone");
        }
    }

    private void validateDateTimeMatchesZonePolicy(OffsetDateTime value, ZoneId zone, String fieldName) {
        if (value == null) return;
        ZonedDateTime resolved = resolveLocalDateTime(value.toLocalDateTime(), zone);
        if (!resolved.toInstant().equals(value.toInstant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    fieldName + " offset is inconsistent with timezone " + zone.getId()
                            + " and its DST resolution policy");
        }
    }

    private void validateOccurrence(OccurrencePlan occurrence) {
        if (!occurrence.startInstant().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Occurrence " + occurrence.index() + " must start in the future");
        }
        if (!occurrence.startInstant().isBefore(occurrence.endInstant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Occurrence " + occurrence.index() + " end must be after start");
        }
        Duration duration = Duration.between(occurrence.startInstant(), occurrence.endInstant());
        if (duration.compareTo(Duration.ofMinutes(15)) < 0 || duration.compareTo(Duration.ofHours(8)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Occurrence " + occurrence.index() + " must last between 15 minutes and 8 hours");
        }
    }

    private List<OccurrencePlan> expandSeries(ReservationSeriesInput input, ZoneId zone) {
        LocalDateTime firstLocalStart = input.startsAt().toLocalDateTime();
        LocalDateTime firstLocalEnd = input.endsAt().toLocalDateTime();
        List<OccurrencePlan> occurrences = new ArrayList<>();
        for (int i = 0; i < input.occurrences(); i++) {
            LocalDateTime localStart = firstLocalStart.plusWeeks(i);
            LocalDateTime localEnd = firstLocalEnd.plusWeeks(i);
            ZonedDateTime start = resolveLocalDateTime(localStart, zone);
            ZonedDateTime end = resolveLocalDateTime(localEnd, zone);
            occurrences.add(new OccurrencePlan(i + 1, start.toInstant(), end.toInstant(),
                    start.toOffsetDateTime(), end.toOffsetDateTime()));
        }
        return occurrences;
    }

    private ZonedDateTime resolveLocalDateTime(LocalDateTime localDateTime, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> validOffsets = rules.getValidOffsets(localDateTime);
        if (validOffsets.size() == 1) {
            return ZonedDateTime.ofLocal(localDateTime, zone, validOffsets.get(0));
        }
        if (validOffsets.size() > 1) {
            ZoneOffset preferred = validOffsets.get(0);
            for (ZoneOffset candidate : validOffsets) {
                if (localDateTime.toInstant(candidate).isBefore(localDateTime.toInstant(preferred))) {
                    preferred = candidate;
                }
            }
            return ZonedDateTime.ofLocal(localDateTime, zone, preferred);
        }
        ZoneOffsetTransition transition = rules.getTransition(localDateTime);
        if (transition != null && transition.isGap()) {
            LocalDateTime shifted = localDateTime.plus(transition.getDuration());
            return ZonedDateTime.ofLocal(shifted, zone, transition.getOffsetAfter());
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unable to resolve local time " + localDateTime + " in timezone " + zone.getId());
    }

    private boolean hasAnyRole(Authentication auth, String... roles) {
        return auth.getAuthorities().stream().anyMatch(a -> java.util.Arrays.asList(roles).contains(a.getAuthority()));
    }

    private record OccurrencePlan(int index, Instant startInstant, Instant endInstant,
                                 OffsetDateTime startOffset, OffsetDateTime endOffset) {
    }
}
