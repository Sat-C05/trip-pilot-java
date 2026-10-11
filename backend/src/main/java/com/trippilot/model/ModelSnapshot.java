package com.trippilot.model;

import jakarta.validation.constraints.*;
import java.net.URI;

/** Configuration identifiers only: never include credentials in persisted snapshots. */
public record ModelSnapshot(
        @NotBlank String provider,
        @NotBlank String modelId,
        @NotBlank String artifactFingerprint,
        @NotBlank String runtimeVersion,
        @NotNull URI baseUrl,
        @Min(4096) @Max(8192) int contextTokens,
        @NotBlank String promptVersion) {
    @AssertTrue
    public boolean isCredentialFreeEndpoint() {
        return baseUrl != null && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null && baseUrl.getFragment() == null
                && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()));
    }
}
