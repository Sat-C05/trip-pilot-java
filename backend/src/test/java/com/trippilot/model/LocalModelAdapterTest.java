package com.trippilot.model;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in real inference: uses an explicitly configured, dedicated one-slot runtime. */
@EnabledIfEnvironmentVariable(named = "PHASE2_MODEL_TESTS", matches = "true")
class LocalModelAdapterTest {
    @Test void pinnedInstalledArtifactRespondsThroughProductionAdapter() {
        var model = new ModelSnapshot("ollama", "gemma4:e4b",
                "c6eb396dbd5992bbe3f5cdb947e8bbc0ee413d7c17e2beaae69f5d569cf982eb", "0.20.4",
                URI.create(System.getenv("MODEL_BASE_URL")), 4096, "research-v1");
        String schema = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"UNAVAILABLE\"]}},\"required\":[\"status\"],\"additionalProperties\":false}";
        var adapter = new OllamaModelAdapter();
        ModelAdapter.Response response;
        try {
            response = adapter.infer(new InferenceConfiguration(model, 0, 42, 128, 90),
                    new ModelAdapter.Request("Use only supplied evidence. You have no action tools. Missing evidence yields UNAVAILABLE. Return a JSON object with exactly status. Do not use Markdown fences or rename keys. JSON schema: " + schema,
                    "This synthetic case has no evidence. Return the object {\"status\":\"UNAVAILABLE\"} with no other text.", schema), () -> false);
        } finally { adapter.close(); }
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.text());
        assertEquals("UNAVAILABLE", json.path("status").asText()); assertEquals(1, json.size());
        assertTrue(response.inputTokens() > 0); assertTrue(response.outputTokens() > 0);
    }
}
