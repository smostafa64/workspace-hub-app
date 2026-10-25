package com.example.workspace.api;

import java.util.List;
import java.util.UUID;

public record ReservationSeriesView(UUID seriesId,
                                   Long resourceId,
                                   String title,
                                   String timezone,
                                   String frequency,
                                   int occurrences,
                                   List<ReservationView> reservations) { }
