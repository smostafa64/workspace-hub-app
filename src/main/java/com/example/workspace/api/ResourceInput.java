package com.example.workspace.api;

import com.example.workspace.domain.ResourceType;
import jakarta.validation.constraints.*;

public record ResourceInput(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull ResourceType type,
        @NotBlank String building,
        Integer floorNumber,
        @Min(1) int capacity,
        boolean active,
        boolean approvalRequired) { }
