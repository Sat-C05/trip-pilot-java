package com.trippilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TripPilotPropertiesTest {
    private final URI localModel = URI.create("http://127.0.0.1:11434");
    private final URI provider = URI.create("http://127.0.0.1:8081");

    @Test
    void acceptsBothEvaluationContextLimits() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (int context : new int[] {4096, 8192}) {
                var properties = properties(3, 1, context);
                assertTrue(factory.getValidator().validate(properties).isEmpty());
            }
        }
    }

    @Test
    void rejectsUnsafeConcurrencyAndContextOutsideEvaluationRange() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (int context : new int[] {4095, 8193}) {
                Set<String> paths = factory.getValidator().validate(properties(4, 2, context))
                        .stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
                assertEquals(Set.of("research.workers", "model.inferenceConcurrency", "model.contextTokens"), paths);
            }
            assertEquals(2, factory.getValidator().validate(properties(0, 0, 4096)).size());
        }
    }

    @Test
    void rejectsMissingConfigurationSectionsAndEndpoints() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertEquals(3, factory.getValidator().validate(new TripPilotProperties(null, null, null)).size());
            var properties = new TripPilotProperties(new TripPilotProperties.Research(3),
                    new TripPilotProperties.Model(null, "", 1, 4096),
                    new TripPilotProperties.Booking(false, null));
            assertEquals(3, factory.getValidator().validate(properties).size());
        }
    }

    private TripPilotProperties properties(int workers, int inference, int context) {
        return new TripPilotProperties(new TripPilotProperties.Research(workers),
                new TripPilotProperties.Model(localModel, "unconfigured", inference, context),
                new TripPilotProperties.Booking(false, provider));
    }
}
