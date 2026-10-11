package com.trippilot.model;

import static com.trippilot.model.ModelFailure.Code.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Tool-free GenerateContent adapter. No implicit retries or provider error-body logging. */
@Component
public final class GeminiModelAdapter implements ModelAdapter {
    public static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com");
    private final String apiKey;
    private final URI allowedEndpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiModelAdapter(@Value("${GEMINI_API_KEY:}") String apiKey) { this(apiKey, ENDPOINT); }
    // Package-private endpoint override is only for in-process transport fixtures.
    GeminiModelAdapter(String apiKey, URI endpoint) { this.apiKey = apiKey; this.allowedEndpoint = endpoint; }
    @jakarta.annotation.PreDestroy public void close() { client.shutdownNow(); }
    /** Configuration identity, NOT a downloadable cloud-weight digest or lineage attestation. */
    public static String fingerprint(String modelId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("gemini:" + modelId + ":v1beta:generateContent-v1").getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public Response infer(InferenceConfiguration config, Request request, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.callSeconds());
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            check(cancelled, deadline);
            var model = config.model();
            if (!"gemini".equals(model.provider()) || !allowedEndpoint.equals(model.baseUrl())
                    || !"v1beta".equals(model.runtimeVersion()) || !model.modelId().matches("gemini-[a-zA-Z0-9.-]+")
                    || !fingerprint(model.modelId()).equals(model.artifactFingerprint())) throw new ModelFailure(SNAPSHOT_MISMATCH);
            if (apiKey.isBlank()) throw new ModelFailure(UNAVAILABLE);
            var schema = json.readTree(request.schemaJson());
            if (schema == null || !schema.isObject()) throw new ModelFailure(INVALID_RESPONSE);
            var body = Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.prompt())))),
                    "generationConfig", Map.of("temperature", config.temperature(), "seed", config.seed(),
                            "candidateCount", 1, "maxOutputTokens", config.outputTokens(),
                            "responseMimeType", "application/json", "responseJsonSchema", schema));
            var http = HttpRequest.newBuilder(URI.create(allowedEndpoint + "/v1beta/models/" + model.modelId() + ":generateContent"))
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                    .header("x-goog-api-key", apiKey).header("Content-Type", "application/json")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            future = client.sendAsync(http, ignored -> new OllamaModelAdapter.LimitedBody());
            HttpResponse<byte[]> response;
            while (true) {
                check(cancelled, deadline);
                try { response = future.get(50, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException ignored) { }
            }
            if (response.statusCode() != 200) throw new ModelFailure(UNAVAILABLE);
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw new ModelFailure(INVALID_RESPONSE);
            var result = json.readTree(response.body());
            if (!model.modelId().equals(result.path("modelVersion").asText())) throw new ModelFailure(SNAPSHOT_MISMATCH);
            var candidates = result.path("candidates");
            if (!candidates.isArray() || candidates.size() != 1) throw new ModelFailure(INVALID_RESPONSE);
            var candidate = candidates.get(0);
            if ("MAX_TOKENS".equals(candidate.path("finishReason").asText())) throw new ModelFailure(OUTPUT_LIMIT);
            if (!"STOP".equals(candidate.path("finishReason").asText())
                    || !"model".equals(candidate.path("content").path("role").asText())) throw new ModelFailure(INVALID_RESPONSE);
            var parts = candidate.path("content").path("parts");
            if (!parts.isArray() || parts.size() != 1 || !parts.get(0).path("text").isString()
                    || parts.get(0).has("functionCall") || parts.get(0).path("thought").asBoolean()) throw new ModelFailure(INVALID_RESPONSE);
            var usage = result.path("usageMetadata");
            for (String key : List.of("promptTokenCount", "candidatesTokenCount", "totalTokenCount"))
                if (!usage.path(key).isIntegralNumber() || !usage.path(key).canConvertToInt()) throw new ModelFailure(INVALID_RESPONSE);
            int input = usage.path("promptTokenCount").asInt(), output = usage.path("candidatesTokenCount").asInt(),
                    total = usage.path("totalTokenCount").asInt();
            if (input < 0 || output < 0 || total < (long) input + output) throw new ModelFailure(INVALID_RESPONSE);
            if (total - (long) input > config.outputTokens()) throw new ModelFailure(OUTPUT_LIMIT);
            if (total >= model.contextTokens()) throw new ModelFailure(CONTEXT_LIMIT);
            String text = parts.get(0).path("text").asText();
            if (text.isBlank()) throw new ModelFailure(INVALID_RESPONSE);
            check(cancelled, deadline);
            return new Response(text, input, total - input);
        } catch (ModelFailure error) { throw error;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ModelFailure(CANCELLED);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof ModelFailure failure) throw failure;
            throw new ModelFailure(error.getCause() instanceof HttpTimeoutException ? DEADLINE : UNAVAILABLE);
        } catch (Exception error) { throw new ModelFailure(INVALID_RESPONSE);
        } finally { if (future != null && !future.isDone()) future.cancel(true); }
    }
    private static void check(BooleanSupplier cancelled, long deadline) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ModelFailure(CANCELLED);
        if (System.nanoTime() >= deadline) throw new ModelFailure(DEADLINE);
    }
}
