package com.nada.connections.http;

/** An error that maps directly to an HTTP status (e.g. unknown route, wrong method). */
final class HttpError extends RuntimeException {
    private final int status;
    private final String allow;

    HttpError(int status, String message) {
        this(status, message, null);
    }

    HttpError(int status, String message, String allow) {
        super(message);
        this.status = status;
        this.allow = allow;
    }

    int status() {
        return status;
    }

    /** Value for the Allow header on 405 responses, otherwise null. */
    String allow() {
        return allow;
    }
}
