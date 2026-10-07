package com.videoservice.worker;

final class MediaFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    MediaFailure(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    String code() { return code; }
    boolean retryable() { return retryable; }
}
