package com.trippilot.validation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Only backend-assembled records are public results. Excerpts are attributed, not universal truth. */
public record ValidatedResearch(String validatorVersion, UUID revisionId, Instant validatedAt,
        boolean complete, List<String> missingRequirements, List<Field> fields, List<Decision> decisions,
        Estimate durationEstimate, String costEstimateReason) {
    public enum Name { DESCRIPTION, STAY, TRANSPORT, ACTIVITY, PRICE, AVAILABILITY, POLICY }
    public record Citation(UUID sourceId, UUID passageId, String url, Instant retrievedAt, String quote) {}
    public record Field(Name field, FieldState state, String value, String reason, List<Citation> citations) {}
    public record Decision(Name field, String code) {}
    public record Estimate(FieldState state, Long nights, String reason, UUID inputRevisionId,
            String arrival, String departure, List<String> assumptions) {}
}
