package com.trippilot.model;

import java.util.function.BooleanSupplier;

/** No tools or action execution. Text is an unvalidated proposal, never a factual result. */
public interface ModelAdapter {
    record Request(String system, String prompt, String schemaJson) {
        public Request {
            if (system == null || prompt == null || schemaJson == null || prompt.isBlank()
                    || system.length() + prompt.length() + schemaJson.length() > 30000) {
                throw new IllegalArgumentException("Invalid or oversized model request");
            }
        }
    }
    record Response(String text, int inputTokens, int outputTokens) {}
    Response infer(InferenceConfiguration configuration, Request request, BooleanSupplier cancelled);
}
