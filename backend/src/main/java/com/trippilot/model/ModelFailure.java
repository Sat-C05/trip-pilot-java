package com.trippilot.model;

/** Safe error code only; raw provider bodies, prompts and transport errors are excluded. */
public final class ModelFailure extends RuntimeException {
    public enum Code { CANCELLED, DEADLINE, UNAVAILABLE, SNAPSHOT_MISMATCH, INVALID_RESPONSE, OUTPUT_LIMIT, CONTEXT_LIMIT }
    private final Code code;
    private final Integer httpStatus;
    public ModelFailure(Code code) { this(code, null); }
    public ModelFailure(Code code, Integer httpStatus) {
        super(code.name()); this.code = code;
        if(httpStatus != null && (httpStatus < 100 || httpStatus > 599)) throw new IllegalArgumentException("Invalid HTTP status");
        this.httpStatus = httpStatus;
    }
    public Code code() { return code; }
    public Integer httpStatus() { return httpStatus; }
}
