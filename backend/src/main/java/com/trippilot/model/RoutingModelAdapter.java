package com.trippilot.model;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** One application slot across providers, including historical snapshotted jobs. */
@Primary
@Component
public final class RoutingModelAdapter implements ModelAdapter {
    private final OllamaModelAdapter ollama;
    private final GeminiModelAdapter gemini;
    private final Semaphore slot = new Semaphore(1, true);
    public RoutingModelAdapter(OllamaModelAdapter ollama, GeminiModelAdapter gemini) {
        this.ollama = ollama; this.gemini = gemini;
    }
    public Response infer(InferenceConfiguration config, Request request, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.callSeconds());
        boolean acquired = false;
        try {
            while (!acquired) {
                if (cancelled.getAsBoolean()) throw new ModelFailure(ModelFailure.Code.CANCELLED);
                if (System.nanoTime() >= deadline) throw new ModelFailure(ModelFailure.Code.DEADLINE);
                acquired = slot.tryAcquire(50, TimeUnit.MILLISECONDS);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining < TimeUnit.SECONDS.toNanos(1)) throw new ModelFailure(ModelFailure.Code.DEADLINE);
            var bounded = new InferenceConfiguration(config.model(), config.temperature(), config.seed(),
                    config.outputTokens(), (int) TimeUnit.NANOSECONDS.toSeconds(remaining));
            return switch (config.model().provider()) {
                case "ollama" -> ollama.infer(bounded, request, cancelled);
                case "gemini" -> gemini.infer(bounded, request, cancelled);
                default -> throw new ModelFailure(ModelFailure.Code.SNAPSHOT_MISMATCH);
            };
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new ModelFailure(ModelFailure.Code.CANCELLED);
        } finally { if (acquired) slot.release(); }
    }
}
