package com.trippilot.model;

import static com.trippilot.model.ModelFailure.Code.*;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Singleton gate is shared by every job; local runtime must also enforce parallel=1. */
@Component
public final class OllamaModelAdapter implements ModelAdapter {
    private final Semaphore slot = new Semaphore(1, true);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @jakarta.annotation.PreDestroy
    public void close() { client.shutdownNow(); }

    @Override public Response infer(InferenceConfiguration config, Request request, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.callSeconds());
        boolean acquired = false;
        try {
            while (!acquired) {
                check(cancelled, deadline);
                acquired = slot.tryAcquire(50, TimeUnit.MILLISECONDS);
            }
            check(cancelled, deadline);
            var model = config.model();
            if (!"ollama".equals(model.provider())) throw new ModelFailure(SNAPSHOT_MISMATCH);
            var schema = json.readTree(request.schemaJson());
            if (schema == null || !schema.isObject()) throw new ModelFailure(INVALID_RESPONSE);
            var version = exchange(config, "api/version", null, cancelled, deadline);
            if (!model.runtimeVersion().equals(version.path("version").asText())) throw new ModelFailure(SNAPSHOT_MISMATCH);
            var tags = exchange(config, "api/tags", null, cancelled, deadline);
            boolean found = false;
            for (var tag : tags.path("models")) {
                if (model.modelId().equals(tag.path("name").asText())
                        && model.artifactFingerprint().equals(tag.path("digest").asText())) found = true;
            }
            if (!found) throw new ModelFailure(SNAPSHOT_MISMATCH);
            var body = Map.of("model", model.modelId(), "stream", false, "think", false, "format", schema,
                    "keep_alive", "5m", "messages", List.of(Map.of("role", "system", "content", request.system()),
                    Map.of("role", "user", "content", request.prompt())),
                    "options", Map.of("num_ctx", model.contextTokens(), "num_predict", config.outputTokens(),
                    "temperature", config.temperature(), "seed", config.seed()));
            var result = exchange(config, "api/chat", json.writeValueAsString(body), cancelled, deadline);
            check(cancelled, deadline);
            if (!result.path("done").asBoolean() || !model.modelId().equals(result.path("model").asText())
                    || !"assistant".equals(result.path("message").path("role").asText())
                    || !result.path("message").path("content").isString()
                    || !result.path("prompt_eval_count").isIntegralNumber() || !result.path("eval_count").isIntegralNumber()) {
                throw new ModelFailure(INVALID_RESPONSE);
            }
            int input = result.path("prompt_eval_count").asInt(-1), output = result.path("eval_count").asInt(-1);
            if (input < 0 || output < 0) throw new ModelFailure(INVALID_RESPONSE);
            if ("length".equals(result.path("done_reason").asText()) || output > config.outputTokens()) throw new ModelFailure(OUTPUT_LIMIT);
            if ((long) input + output >= model.contextTokens()) throw new ModelFailure(CONTEXT_LIMIT);
            String text = result.path("message").path("content").asText();
            if (text.isBlank()) throw new ModelFailure(INVALID_RESPONSE);
            return new Response(text, input, output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new ModelFailure(CANCELLED);
        } catch (ModelFailure exception) { throw exception;
        } catch (Exception exception) { throw new ModelFailure(INVALID_RESPONSE);
        } finally { if (acquired) slot.release(); }
    }

    private JsonNode exchange(InferenceConfiguration config, String path, String body,
            BooleanSupplier cancelled, long deadline) throws InterruptedException {
        check(cancelled, deadline);
        var endpoint = config.model().baseUrl();
        String base = endpoint.toString();
        var builder = HttpRequest.newBuilder(java.net.URI.create(base + (base.endsWith("/") ? "" : "/") + path))
                .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                .header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        var future = client.sendAsync(builder.build(), ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response;
            while (true) {
                check(cancelled, deadline);
                try { response = future.get(50, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException ignored) { }
            }
            if (response.statusCode() != 200) throw new ModelFailure(UNAVAILABLE);
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                    .startsWith("application/json")) throw new ModelFailure(INVALID_RESPONSE);
            return json.readTree(response.body());
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ModelFailure failure) throw failure;
            if (exception.getCause() instanceof HttpTimeoutException) throw new ModelFailure(DEADLINE);
            throw new ModelFailure(UNAVAILABLE);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static void check(BooleanSupplier cancelled, long deadline) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ModelFailure(CANCELLED);
        if (System.nanoTime() >= deadline) throw new ModelFailure(DEADLINE);
    }

    /** Bound bytes while receiving, including chunked responses without Content-Length. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 262144) {
                    subscription.cancel(); result.completeExceptionally(new ModelFailure(INVALID_RESPONSE)); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
