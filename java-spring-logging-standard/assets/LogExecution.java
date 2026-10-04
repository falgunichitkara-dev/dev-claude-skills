package com.company.common.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic entry/exit/duration logging via LoggingAspect.
 * Apply to controller endpoints, service entry points, and message listeners.
 * Do not hand-write entry/exit log statements — use this instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogExecution {
    /** Log level for entry/exit lines. Default INFO. */
    LogLevel value() default LogLevel.INFO;

    /**
     * When true, logs the method's arguments (masked via PayloadMasker) at DEBUG
     * on success, AND fail-safe logs them at the exception's level if the method
     * throws — even if DEBUG is off. Set this on the "important places" defined
     * in references/payload-logging.md: API boundaries, outbound external calls,
     * message consumers/producers — not on every internal method.
     */
    boolean logPayload() default false;

    enum LogLevel { DEBUG, INFO }
}
