package com.company.common.exception;

/**
 * Base class for unexpected, infrastructure/dependency-driven failures
 * (DB down, timeout, NPE, external API failure). Logged at ERROR with full
 * stack trace, triggers alert, eligible for retry. See
 * references/exception-hierarchy.md for full policy and retry table.
 */
public abstract class TechnicalException extends RuntimeException {

    private final String errorCode;

    protected TechnicalException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    protected TechnicalException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
