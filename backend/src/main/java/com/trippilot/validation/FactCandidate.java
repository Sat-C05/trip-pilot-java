package com.trippilot.validation;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** An untrusted proposal, not a validated UI fact. Execution uses strict StructuredExtraction candidates. */
public record FactCandidate(@NotBlank @Size(max = 100) String field,
                            @NotBlank @Size(max = 500) String scope,
                            @NotNull FieldState state,
                            @Size(max = 4000) String valueJson,
                            @NotNull @Size(max = 10) List<@NotNull UUID> evidenceIds,
                            @NotBlank @Size(max = 1000) String reason) {
    public FactCandidate {
        if (evidenceIds != null) { evidenceIds = List.copyOf(evidenceIds); }
    }
    @AssertTrue
    public boolean isRepresentationConsistent() {
        if (state == null || evidenceIds == null) { return false; }
        if (evidenceIds.stream().distinct().count() != evidenceIds.size()) { return false; }
        return switch (state) {
            case UNAVAILABLE -> valueJson == null && evidenceIds.isEmpty();
            case CONFLICTING -> valueJson == null && evidenceIds.size() >= 2;
            default -> valueJson != null && !valueJson.isBlank() && !evidenceIds.isEmpty();
        };
    }
}

