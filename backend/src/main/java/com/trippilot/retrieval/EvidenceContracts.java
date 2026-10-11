package com.trippilot.retrieval;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Backend-issued immutable source and passage snapshots; chat messages cannot satisfy these contracts. */
public final class EvidenceContracts {
    private EvidenceContracts() {}
    public record Source(@NotNull UUID id, @NotNull UUID contextId, @NotNull UUID ownerId,
                         @NotNull URI requestedUrl, @NotNull URI finalUrl, @NotNull Instant retrievedAt,
                         @Min(200) @Max(599) int responseStatus, @NotBlank @Size(max = 120) String contentType,
                         @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash,
                         @NotNull @Size(max = 30000) String text) {}
    public record Passage(@NotNull UUID id, @NotNull UUID sourceId, @NotNull UUID contextId, @NotNull UUID ownerId,
                          @Min(0) int startOffset, @Min(1) int endOffset, @NotBlank @Size(max = 3000) String text) {
        @AssertTrue
        public boolean isOffsetRangeConsistent() { return text != null && endOffset > startOffset && endOffset - startOffset == text.length(); }
    }
}
