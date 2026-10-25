package com.example.workspace.api;

import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationView(Long id, Long resourceId, String resourceName, String bookedBy, String title,
                              Instant startsAt, Instant endsAt, ReservationStatus status, UUID seriesId) {
    public static ReservationView of(Reservation r) {
        return new ReservationView(r.getId(), r.getResource().getId(), r.getResource().getName(),
                r.getBookedBy(), r.getTitle(), r.getStartsAt(), r.getEndsAt(), r.getStatus(), r.getSeriesId());
    }
}
