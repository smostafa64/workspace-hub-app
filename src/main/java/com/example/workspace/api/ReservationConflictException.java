package com.example.workspace.api;

import java.util.List;

public class ReservationConflictException extends RuntimeException {
    private final String code = "RESERVATION_CONFLICT";
    private final String message = "One or more occurrences conflict with existing reservations";
    private final List<ReservationConflictItem> conflicts;

    public ReservationConflictException(List<ReservationConflictItem> conflicts) {
        this.conflicts = conflicts;
    }

    public String getCode() { return code; }
    @Override
    public String getMessage() { return message; }
    public List<ReservationConflictItem> getConflicts() { return conflicts; }
}
