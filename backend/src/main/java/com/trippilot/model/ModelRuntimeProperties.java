package com.trippilot.model;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("trippilot.model-runtime")
public record ModelRuntimeProperties(@NotBlank String provider,
        @Pattern(regexp = "[0-9a-f]{64}") @NotNull String artifactFingerprint,
        @NotBlank String runtimeVersion, @NotBlank String promptVersion,
        @DecimalMin("0") @DecimalMax("2") double temperature, int seed) {}
