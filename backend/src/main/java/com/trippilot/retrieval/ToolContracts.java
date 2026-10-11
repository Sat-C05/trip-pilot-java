package com.trippilot.retrieval;

import jakarta.validation.constraints.*;
import java.net.URI;

/** Typed proposals only. Destination/DNS/redirect validation is required before Phase 2 fetches. */
public final class ToolContracts {
    private ToolContracts() {}
    public record Search(@NotBlank @Size(max = 512) String query, @Min(1) @Max(10) int maxResults) {}
    public record Fetch(@NotNull URI url) {
        @AssertTrue
        public boolean isPublicWebFormat() {
            return url != null && url.getHost() != null && url.getUserInfo() == null
                    && ("http".equals(url.getScheme()) || "https".equals(url.getScheme()));
        }
    }
}
