package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;
import com.trippilot.model.ModelSnapshot;
import com.trippilot.observability.TraceRedactor;
import com.trippilot.planning.TravelRequirements;
import com.trippilot.research.HarnessProperties;
import com.trippilot.research.JobState;
import com.trippilot.retrieval.ToolContracts;
import com.trippilot.validation.FactCandidate;
import com.trippilot.validation.FieldState;
import jakarta.validation.Validation;
import java.net.URI;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class FoundationContractsTest {
    private boolean valid(Object value) {
        try (var factory = Validation.buildDefaultValidatorFactory()) { return factory.getValidator().validate(value).isEmpty(); }
    }

    @Test void terminalJobsCannotResumeAndCancellationCannotPublishResults() {
        for (var state : List.of(JobState.SUCCEEDED, JobState.PARTIAL, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED)) {
            for (var next : JobState.values()) { assertFalse(state.permits(next)); }
        }
        assertTrue(JobState.RUNNING.permits(JobState.CANCEL_REQUESTED));
        assertTrue(JobState.CANCEL_REQUESTED.permits(JobState.CANCELLED));
        assertFalse(JobState.CANCEL_REQUESTED.permits(JobState.SUCCEEDED));
        assertFalse(JobState.QUEUED.permits(JobState.SUCCEEDED));
        assertTrue(JobState.RUNNING.permits(JobState.INTERRUPTED));
    }

    @Test void snapshotsAreCredentialFreeAndToolProposalsAreBounded() {
        assertTrue(valid(new ModelSnapshot("ollama", "gemma4:e4b", "digest", "version", URI.create("http://127.0.0.1:11434"), 4096, "v1")));
        for (String endpoint : List.of("http://user:secret@localhost", "http://localhost?api_key=secret", "file:///model")) {
            assertFalse(valid(new ModelSnapshot("ollama", "model", "digest", "version", URI.create(endpoint), 4096, "v1")));
        }
        assertFalse(valid(new ToolContracts.Search(" ", 11)));
        assertTrue(valid(new ToolContracts.Search("Jaipur official tourism", 5)));
        assertFalse(valid(new ToolContracts.Fetch(URI.create("file:///etc/passwd"))));
        assertFalse(valid(new ToolContracts.Fetch(URI.create("https://user:secret@example.com"))));
    }

    @Test void missingEvidenceAndConflictsCannotBecomeValuedFacts() {
        UUID passage = UUID.randomUUID();
        assertTrue(valid(new FactCandidate("price", "trip", FieldState.UNAVAILABLE, null, List.of(), "No quote")));
        assertFalse(valid(new FactCandidate("price", "trip", FieldState.UNAVAILABLE, "2000", List.of(passage), "No quote")));
        assertFalse(valid(new FactCandidate("price", "trip", FieldState.SUPPORTED, "2000", List.of(), "Claim")));
        assertFalse(valid(new FactCandidate("policy", "trip", FieldState.CONFLICTING, "48", List.of(passage, UUID.randomUUID()), "Conflict")));
        assertFalse(valid(new FactCandidate("policy", "trip", FieldState.CONFLICTING, null, List.of(passage, passage), "Duplicate citation")));
        assertTrue(valid(new FactCandidate("policy", "trip", FieldState.CONFLICTING, null, List.of(passage, UUID.randomUUID()), "Conflict")));
    }

    @Test void draftsPreserveMissingInformationButRejectImpossibleScope() {
        assertTrue(valid(new TravelRequirements(null, null, "IN", null, null, null, null, null, List.of())));
        var arrival = LocalDate.of(2026, 12, 10);
        assertFalse(valid(new TravelRequirements("Delhi", "Jaipur", "US", arrival, arrival.minusDays(1), new BigDecimal("-1"), "XXX", 0, List.of())));
        assertTrue(valid(new TravelRequirements("Delhi", "Jaipur", "IN", arrival, arrival.plusDays(1), new BigDecimal("2000.00"), "INR", 2, List.of("museums"))));
    }

    @Test void budgetsCannotPermitUnlimitedRepairsOrCallsLongerThanJobs() {
        assertTrue(valid(policy(1, 90, 600)));
        assertFalse(valid(policy(2, 90, 600)));
        assertFalse(valid(policy(1, 90, 30)));
    }

    private HarnessProperties policy(int repairs, int callSeconds, int jobSeconds) {
        return new HarnessProperties("v1", 12, 8, 12, repairs, jobSeconds, callSeconds, 15, 2097152, 30000, 768, 6, 15, 7, 30, 14);
    }

    @Test void nestedTracesLoseCredentialsAndKnownTravelerSecrets() {
        var input = Map.<String, Object>of("request", Map.of("API_key", "secret-key", "Authorization", "Bearer abc", "passport", "P123"),
                "outputs", List.of("Contact person@example.com; Bearer tokenABC; AliceSensitive"), "durationMs", 50);
        var output = TraceRedactor.redact(input, List.of("AliceSensitive"));
        String text = output.toString();
        for (String secret : List.of("secret-key", "Bearer abc", "P123", "person@example.com", "tokenABC", "AliceSensitive")) { assertFalse(text.contains(secret)); }
        assertEquals(50, output.get("durationMs"));
        assertTrue(input.toString().contains("secret-key"), "Redaction must not mutate evidence/input");
    }

    @Test void evidenceOffsetsReferToTheExactSavedText() {
        var source = UUID.randomUUID();
        var context = UUID.randomUUID();
        var owner = UUID.randomUUID();
        assertTrue(valid(new com.trippilot.retrieval.EvidenceContracts.Passage(UUID.randomUUID(), source, context, owner, 4, 9, "price")));
        assertFalse(valid(new com.trippilot.retrieval.EvidenceContracts.Passage(UUID.randomUUID(), source, context, owner, 4, 8, "price")));
    }
}
