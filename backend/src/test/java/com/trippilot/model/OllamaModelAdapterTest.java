package com.trippilot.model;

import static org.junit.jupiter.api.Assertions.*;
import static com.trippilot.model.ModelFailure.Code.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class OllamaModelAdapterTest {
    private HttpServer server;
    private ExecutorService workers;
    private final OllamaModelAdapter adapter = new OllamaModelAdapter();
    private final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), calls = new AtomicInteger();
    private final AtomicReference<String> sent = new AtomicReference<>();
    private final AtomicReference<String> digest = new AtomicReference<>("a".repeat(64));
    private final AtomicReference<String> response = new AtomicReference<>(answer(12, 10, "stop"));
    private volatile int delay;
    private final CountDownLatch started = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/api/", exchange -> {
            String body;
            switch (exchange.getRequestURI().getPath()) {
                case "/api/version" -> body = "{\"version\":\"0.20.4\"}";
                case "/api/tags" -> body = "{\"models\":[{\"name\":\"gemma4:e4b\",\"digest\":\"" + digest.get() + "\"}]}";
                default -> {
                    calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    started.countDown();
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    active.decrementAndGet(); body = response.get();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); adapter.close(); }
    private InferenceConfiguration config(int seconds) {
        return new InferenceConfiguration(new ModelSnapshot("ollama", "gemma4:e4b", "a".repeat(64), "0.20.4",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 4096, "research-v1"), 0, 42, 768, seconds);
    }
    private ModelAdapter.Request request() { return new ModelAdapter.Request("No action tools.", "Synthetic input", "{\"type\":\"object\"}"); }
    private static String answer(int input, int output, String reason) {
        return "{\"model\":\"gemma4:e4b\",\"done\":true,\"done_reason\":\"" + reason
                + "\",\"prompt_eval_count\":" + input + ",\"eval_count\":" + output
                + ",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}";
    }
    private void failure(ModelFailure.Code code, Runnable action) { assertEquals(code, assertThrows(ModelFailure.class, action::run).code()); }

    @Test void snapshotOptionsAndNoToolsReachProvider() {
        var result = adapter.infer(config(5), request(), () -> false);
        assertEquals("{}", result.text()); assertEquals(12, result.inputTokens());
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(sent.get());
        assertEquals(4096, json.path("options").path("num_ctx").asInt());
        assertEquals(768, json.path("options").path("num_predict").asInt());
        assertEquals(42, json.path("options").path("seed").asInt());
        assertFalse(json.path("stream").asBoolean()); assertFalse(json.path("think").asBoolean());
        assertFalse(json.has("tools")); assertTrue(json.path("format").isObject());
    }
    @Test void changedArtifactNeverGeneratesOrLeaksBodies() {
        digest.set("b".repeat(64));
        failure(SNAPSHOT_MISMATCH, () -> adapter.infer(config(5), request(), () -> false));
        assertEquals(0, calls.get());
    }
    @Test void threeCallersShareOneSlot() throws Exception {
        delay = 150;
        try (var callers = Executors.newFixedThreadPool(3)) {
            var one = callers.submit(() -> adapter.infer(config(5), request(), () -> false));
            var two = callers.submit(() -> adapter.infer(config(5), request(), () -> false));
            var three = callers.submit(() -> adapter.infer(config(5), request(), () -> false));
            one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS); three.get(5, TimeUnit.SECONDS);
            assertEquals(1, maximum.get()); assertEquals(3, calls.get());
        }
    }
    @Test void waitingCancellationDoesNotCallProvider() throws Exception {
        delay = 300;
        try (var callers = Executors.newSingleThreadExecutor()) {
            var first = callers.submit(() -> adapter.infer(config(5), request(), () -> false));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            failure(CANCELLED, () -> adapter.infer(config(5), request(), () -> true));
            first.get(5, TimeUnit.SECONDS); assertEquals(1, calls.get());
        }
    }
    @Test void activeCancellationDiscardsLateResultAndSlotRecovers() throws Exception {
        delay = 250; var cancelled = new AtomicBoolean();
        try (var callers = Executors.newSingleThreadExecutor()) {
            var result = callers.submit(() -> adapter.infer(config(5), request(), cancelled::get));
            assertTrue(started.await(2, TimeUnit.SECONDS)); cancelled.set(true);
            var exception = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertEquals(CANCELLED, ((ModelFailure) exception.getCause()).code());
            delay = 0; assertEquals("{}", adapter.infer(config(5), request(), () -> false).text());
        }
    }
    @Test void deadlineIncludesWaitingForSlotAndTransport() throws Exception {
        delay = 1500;
        try (var callers = Executors.newSingleThreadExecutor()) {
            var first = callers.submit(() -> adapter.infer(config(5), request(), () -> false));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            failure(DEADLINE, () -> adapter.infer(config(1), request(), () -> false));
            first.get(5, TimeUnit.SECONDS);
        }
        failure(DEADLINE, () -> adapter.infer(config(1), request(), () -> false));
    }
    @Test void invalidTruncatedAndOversizedOutputCannotBeAccepted() {
        response.set("secret provider body is not JSON");
        var error = assertThrows(ModelFailure.class, () -> adapter.infer(config(5), request(), () -> false));
        assertEquals(INVALID_RESPONSE, error.code()); assertFalse(error.toString().contains("secret")); assertNull(error.getCause());
        response.set(answer(12, 768, "length")); failure(OUTPUT_LIMIT, () -> adapter.infer(config(5), request(), () -> false));
        response.set(answer(4000, 100, "stop")); failure(CONTEXT_LIMIT, () -> adapter.infer(config(5), request(), () -> false));
        response.set("x".repeat(300000)); failure(INVALID_RESPONSE, () -> adapter.infer(config(5), request(), () -> false));
        response.set(answer(12, 10, "stop")); assertEquals("{}", adapter.infer(config(5), request(), () -> false).text());
    }
    @Test void inferenceSnapshotRoundTripsWithoutEndpointCredentials() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var original = config(5);
        assertEquals(original, json.readValue(json.writeValueAsString(original), InferenceConfiguration.class));
        assertThrows(IllegalArgumentException.class, () -> new InferenceConfiguration(new ModelSnapshot("ollama", "model", "digest",
                "runtime", URI.create("http://user:secret@localhost"), 4096, "v1"), 0, 42, 768, 90));
    }
}
