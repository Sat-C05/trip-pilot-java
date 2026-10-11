package com.trippilot.model;

/** Safe error code only; raw provider bodies, prompts and transport errors are excluded. */
public final class ModelFailure extends RuntimeException {
    public enum Code { CANCELLED, DEADLINE, UNAVAILABLE, SNAPSHOT_MISMATCH, INVALID_RESPONSE, OUTPUT_LIMIT, CONTEXT_LIMIT }
    private final Code code;
    public ModelFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
