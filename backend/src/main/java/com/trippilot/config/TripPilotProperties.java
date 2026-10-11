package com.trippilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration contracts only. Execution limits are implemented in the research phase. */
@Validated
@ConfigurationProperties(prefix = "trippilot")
public record TripPilotProperties(
        @NotNull @Valid Research research,
        @NotNull @Valid Model model,
        @NotNull @Valid Booking booking) {
    public record Research(@Min(1) @Max(3) int workers) {}

    public record Model(
            @NotNull URI baseUrl,
            @NotBlank String modelId,
            @Min(1) @Max(1) int inferenceConcurrency,
            @Min(4096) @Max(8192) int contextTokens) {}

    public record Booking(boolean enabled, @NotNull URI providerBaseUrl) {}
}
