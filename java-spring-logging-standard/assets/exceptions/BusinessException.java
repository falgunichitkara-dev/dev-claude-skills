package com.company.common.exception;

/**
 * Base class for expected, business-rule-driven failures (bad input, invalid
 * state, duplicate resource, etc). Logged at WARN with no stack trace, not
 * retried, no alert. See references/exception-hierarchy.md for full policy.
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
