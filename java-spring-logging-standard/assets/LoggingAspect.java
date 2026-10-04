package com.company.common.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Powers @LogExecution. Logs method entry, exit, duration, and exceptions
 * so individual methods stay free of manual tracing log lines.
 *
 * When logPayload=true: logs (masked) arguments at DEBUG on success, and
 * fail-safe logs them at the exception's level on failure — regardless of
 * configured log level — so the payload is captured the one time it's
 * actually needed. See references/payload-logging.md.
 */
@Aspect
@Component
public class LoggingAspect {

    private final PayloadMasker payloadMasker;

    public LoggingAspect(PayloadMasker payloadMasker) {
        this.payloadMasker = payloadMasker;
    }

    @Around("@annotation(logExecution)")
    public Object logMethodExecution(ProceedingJoinPoint joinPoint, LogExecution logExecution) throws Throwable {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        String methodName = joinPoint.getSignature().getName();
        long start = System.currentTimeMillis();

        logAtLevel(log, logExecution.value(), "Entering {} with args={}", methodName, joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            logAtLevel(log, logExecution.value(), "Exiting {} in {}ms", methodName, duration);

            // Payload logging on success — DEBUG only, masked. High-volume, so gated by level.
            if (logExecution.logPayload() && log.isDebugEnabled()) {
                log.debug("{} payload: args={} result={}", methodName,
                        payloadMasker.mask(joinPoint.getArgs()), payloadMasker.mask(result));
            }
            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            boolean isBusiness = ex instanceof com.company.common.exception.BusinessException;

            if (isBusiness) {
                log.warn("{} failed after {}ms: {}", methodName, duration, ex.getMessage());
            } else {
                log.error("{} failed after {}ms: {}", methodName, duration, ex.getMessage(), ex);
            }

            // Fail-safe payload capture: always log on failure if logPayload=true,
            // regardless of configured level — this is the moment you actually need it.
            if (logExecution.logPayload()) {
                String maskedArgs = payloadMasker.mask(joinPoint.getArgs());
                if (isBusiness) {
                    log.warn("{} failing payload: args={}", methodName, maskedArgs);
                } else {
                    log.error("{} failing payload: args={}", methodName, maskedArgs);
                }
            }
            throw ex;
        }
    }

    private void logAtLevel(Logger log, LogExecution.LogLevel level, String msg, Object... args) {
        if (level == LogExecution.LogLevel.DEBUG) {
            log.debug(msg, args);
        } else {
            log.info(msg, args);
        }
    }
}
