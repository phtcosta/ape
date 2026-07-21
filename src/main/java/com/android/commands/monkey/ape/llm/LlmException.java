package com.android.commands.monkey.ape.llm;

/**
 * Thrown when the LLM client encounters an HTTP error, timeout, or response parsing failure.
 *
 * <p>{@link #getStatusCode()} carries the HTTP status for the non-200 case ({@code -1} when the
 * failure is not an HTTP status error), so the caller can classify {@code http_<status>} without
 * parsing the message string.
 */
public class LlmException extends RuntimeException {

    private final int statusCode;

    public LlmException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP-status failure: carries the non-200 status code for cause classification. */
    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP status of a non-200 failure, or {@code -1} when the failure is not HTTP-status-based. */
    public int getStatusCode() {
        return statusCode;
    }
}
