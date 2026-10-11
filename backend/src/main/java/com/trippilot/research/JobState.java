package com.trippilot.research;

public enum JobState {
    QUEUED, RUNNING, CANCEL_REQUESTED, CANCELLED, SUCCEEDED, PARTIAL, FAILED, INTERRUPTED;

    public boolean permits(JobState next) {
        return switch (this) {
            case QUEUED -> next == RUNNING || next == CANCELLED;
            case RUNNING -> next == CANCEL_REQUESTED || next == SUCCEEDED || next == PARTIAL || next == FAILED || next == INTERRUPTED;
            case CANCEL_REQUESTED -> next == CANCELLED || next == INTERRUPTED;
            default -> false;
        };
    }
}
