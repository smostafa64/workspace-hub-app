package com.example.workspace.api;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record BookingInput(@NotNull Long resourceId,
                           @NotBlank @Size(max = 120) String title,
                           @NotNull Instant startsAt,
                           @NotNull Instant endsAt) { }
