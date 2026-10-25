package com.example.workspace.api;

import java.time.OffsetDateTime;

public record ReservationConflictItem(int occurrenceIndex, OffsetDateTime startsAt, OffsetDateTime endsAt) { }
