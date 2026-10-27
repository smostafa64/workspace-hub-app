package com.example.workspace.api;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public record ReservationSeriesInput(@NotNull Long resourceId,
                                     @NotBlank @Size(max = 120) String title,
                                     @NotNull OffsetDateTime startsAt,
                                     @NotNull OffsetDateTime endsAt,
                                     @NotBlank String timezone,
                                     @NotBlank String frequency,
                                     @NotNull @Min(2) @Max(12) Integer occurrences) { }
