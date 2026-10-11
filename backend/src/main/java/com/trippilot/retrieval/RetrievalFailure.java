package com.trippilot.retrieval;

public final class RetrievalFailure extends RuntimeException {
    public enum Code { UNSAFE_URL, DNS_FAILURE, REDIRECT_LIMIT, HTTP_FAILURE, UNSUPPORTED_TYPE, TOO_LARGE, EMPTY_CONTENT, DEADLINE, CANCELLED, SEARCH_INVALID }
    private final Code code;
    public RetrievalFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
