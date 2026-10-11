package com.trippilot.model;

import java.util.Objects;

/** Entire immutable inference policy, safe to persist in research_jobs.model_snapshot. */
public record InferenceConfiguration(ModelSnapshot model, double temperature, int seed,
        int outputTokens, int callSeconds) {
    public InferenceConfiguration {
        Objects.requireNonNull(model);
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || outputTokens < 128 || outputTokens > 1024 || callSeconds < 1 || callSeconds > 180) {
            throw new IllegalArgumentException("Invalid inference policy");
        }
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            if (!factory.getValidator().validate(model).isEmpty()) {
                throw new IllegalArgumentException("Invalid model snapshot");
            }
        }
    }
}
