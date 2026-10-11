package com.trippilot.research;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Immutable, validated policy snapshot; Phase 2 owns execution and counters. */
@Validated
@ConfigurationProperties("trippilot.harness")
public record HarnessProperties(
        @NotBlank String version,
        @Min(1) @Max(24) int modelTurns,
        @Min(1) @Max(20) int searches,
        @Min(1) @Max(32) int pages,
        @Min(0) @Max(1) int outputRepairs,
        @Min(30) @Max(1800) int jobSeconds,
        @Min(1) @Max(180) int modelCallSeconds,
        @Min(1) @Max(30) int fetchSeconds,
        @Min(1024) @Max(2097152) int pageBytes,
        @Min(1000) @Max(30000) int sourceCharacters,
        @Min(128) @Max(1024) int outputTokens,
        @Min(1) @Max(72) int priceFreshHours,
        @Min(1) @Max(60) int availabilityFreshMinutes,
        @Min(1) @Max(30) int policyFreshDays,
        @Min(1) @Max(90) int descriptionFreshDays,
        @Min(1) @Max(90) int traceRetentionDays) {

    @AssertTrue(message = "A model call must fit inside the job deadline")
    public boolean isDeadlineConsistent() { return modelCallSeconds <= jobSeconds; }
}
