package com.trippilot.model;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.model.ModelFailure.Code.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class GeminiModelAdapterTest {
    private HttpServer server;
    private ExecutorService workers;
    private GeminiModelAdapter adapter;
    private URI endpoint;
    private final AtomicReference<String> sent = new AtomicReference<>(), key = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
    private String response = answer(12, 10, 22, "STOP", "gemini-test-001");
    private int status = 200, delay = 0;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new GeminiModelAdapter("fixture-key", endpoint);
        server.createContext("/v1beta/models/gemini-test-001:generateContent", exchange -> {
            calls.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            assertNull(exchange.getRequestURI().getQuery());
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delay); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { active.decrementAndGet(); exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); adapter.close(); }
    private InferenceConfiguration config(int seconds) {
        return new InferenceConfiguration(new ModelSnapshot("gemini", "gemini-test-001",
                GeminiModelAdapter.fingerprint("gemini-test-001"), "v1beta", endpoint, 4096, "extractive-v2"), 0, 42, 768, seconds);
    }
    private ModelAdapter.Request request() { return new ModelAdapter.Request("No tools", "Synthetic evidence", "{\"type\":\"object\"}"); }
    static String answer(int input, int output, int total, String reason, String version) {
        return "{\"modelVersion\":\"" + version + "\",\"usageMetadata\":{\"promptTokenCount\":" + input
                + ",\"candidatesTokenCount\":" + output + ",\"totalTokenCount\":" + total
                + "},\"candidates\":[{\"finishReason\":\"" + reason
                + "\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"{}\"}]}}]}";
    }
    private void failure(ModelFailure.Code code) { assertEquals(code, assertThrows(ModelFailure.class,
            () -> adapter.infer(config(3), request(), () -> false)).code()); }
    @Test void capturedPolicyAndServerOnlyKeyWithoutTools() {
        assertEquals("{}", adapter.infer(config(3), request(), () -> false).text());
        assertEquals("fixture-key", key.get());
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(sent.get());
        assertFalse(body.has("tools")); assertFalse(sent.get().contains("fixture-key"));
        assertEquals(768, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertTrue(body.path("generationConfig").path("responseJsonSchema").isObject());
    }
    @Test void quotaFailureIsSingleCallAndRedacted() {
        status = 429; response = "private provider text fixture-key";
        var error = assertThrows(ModelFailure.class, () -> adapter.infer(config(3), request(), () -> false));
        assertEquals(UNAVAILABLE, error.code()); assertEquals(1, calls.get()); assertNull(error.getCause());
        assertEquals(429,error.httpStatus());
        assertFalse(error.toString().contains("fixture-key"));
    }
    @Test void exactVersionMustMatch() { response = answer(12, 10, 22, "STOP", "gemini-changed"); failure(SNAPSHOT_MISMATCH); }
    @Test void truncatedBlockedMalformedAndOversizedCannotPublish() {
        response = answer(12, 10, 22, "MAX_TOKENS", "gemini-test-001"); failure(OUTPUT_LIMIT);
        response = answer(12, 10, 22, "SAFETY", "gemini-test-001"); failure(INVALID_RESPONSE);
        response = "not-json"; failure(INVALID_RESPONSE);
        response = "x".repeat(300000); failure(INVALID_RESPONSE);
    }
    @Test void thinkingTokensAreChargedAndContextBounded() {
        response = answer(12, 10, 800, "STOP", "gemini-test-001"); failure(OUTPUT_LIMIT);
        response = answer(4000, 10, 4100, "STOP", "gemini-test-001"); failure(CONTEXT_LIMIT);
        response = answer(12, 10, 5, "STOP", "gemini-test-001"); failure(INVALID_RESPONSE);
    }
    @Test void endpointMismatchAndMissingKeyMakeNoRequest() {
        var cfg = config(3);
        var changed = new ModelSnapshot("gemini", "gemini-test-001", cfg.model().artifactFingerprint(), "v1beta",
                URI.create("https://other.example"), 4096, "extractive-v2");
        assertEquals(SNAPSHOT_MISMATCH, assertThrows(ModelFailure.class, () -> adapter.infer(
                new InferenceConfiguration(changed, 0, 42, 768, 3), request(), () -> false)).code());
        var empty = new GeminiModelAdapter("", endpoint);
        try { assertEquals(UNAVAILABLE, assertThrows(ModelFailure.class,
                () -> empty.infer(cfg, request(), () -> false)).code()); } finally { empty.close(); }
        assertEquals(0, calls.get());
    }
    @Test void cancellationAndDeadlineDiscardResponse() {
        assertEquals(CANCELLED, assertThrows(ModelFailure.class,
                () -> adapter.infer(config(3), request(), () -> true)).code());
        assertEquals(0, calls.get()); delay = 1500;
        assertEquals(DEADLINE, assertThrows(ModelFailure.class,
                () -> adapter.infer(config(1), request(), () -> false)).code());
    }
    @Test void routerSerializesGeminiCalls() throws Exception {
        delay = 150;
        var ollama = new OllamaModelAdapter(); var router = new RoutingModelAdapter(ollama, adapter);
        try (var callers = Executors.newFixedThreadPool(3)) {
            var one = callers.submit(() -> router.infer(config(5), request(), () -> false));
            var two = callers.submit(() -> router.infer(config(5), request(), () -> false));
            var three = callers.submit(() -> router.infer(config(5), request(), () -> false));
            one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS); three.get(5, TimeUnit.SECONDS);
            assertEquals(3, calls.get());
            assertEquals(1, maximum.get());
        } finally { ollama.close(); }
    }
    @Test void activeCancellationDiscardsLateOutput() throws Exception {
        delay = 500; var cancelled = new AtomicBoolean();
        try(var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> adapter.infer(config(3), request(), cancelled::get));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while(calls.get()==0 && System.nanoTime()<until) Thread.sleep(10);
            assertEquals(1,calls.get()); cancelled.set(true);
            assertEquals(CANCELLED,((ModelFailure)assertThrows(ExecutionException.class,
                    () -> result.get(2,TimeUnit.SECONDS)).getCause()).code());
        }
    }
    @Test void unsolicitedFunctionCallCannotBeAccepted() {
        response = response.replace("\"text\":\"{}\"", "\"functionCall\":{\"name\":\"book\"}");
        failure(INVALID_RESPONSE);
    }
    @Test void admissionSpacingConsumesDeadlineAndCancelledWaitNeverCallsProvider() {
        var paced = new GeminiModelAdapter("fixture-key",endpoint,1500);
        try {
            assertEquals("{}",paced.infer(config(3),request(),()->false).text());
            assertEquals(CANCELLED,assertThrows(ModelFailure.class,
                    () -> paced.infer(config(3),request(),()->true)).code());
            assertEquals(DEADLINE,assertThrows(ModelFailure.class,
                    () -> paced.infer(config(1),request(),()->false)).code());
            assertEquals(1,calls.get());
            assertEquals("{}",paced.infer(config(3),request(),()->false).text());
            assertEquals(2,calls.get());
        } finally {paced.close();}
    }
}
