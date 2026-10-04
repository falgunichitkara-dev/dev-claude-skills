# Exception Hierarchy — Error Hospital Design

## The two root types

```
RuntimeException
├── BusinessException      (expected — bad input, business rule violation)
│   ├── ValidationException
│   ├── DuplicateResourceException
│   ├── InsufficientBalanceException
│   └── InvalidStateTransitionException
└── TechnicalException     (unexpected — infra, dependency, or code failure)
    ├── ExternalServiceException
    ├── DataAccessFailureException
    ├── MessageProcessingException
    └── ConfigurationException
```

Every custom exception in the codebase must extend one of these two — never throw a bare `RuntimeException`, `IllegalStateException`, etc. directly from business logic (framework-internal exceptions from libraries are fine and get mapped in the `GlobalExceptionHandler`).

## Error code format

`<DOMAIN>-<TYPE>-<NUMBER>`, e.g. `ORDER-BIZ-001`, `PAYMENT-TECH-004`.
- `DOMAIN`: the bounded context (ORDER, PAYMENT, INVENTORY, AUTH...)
- `TYPE`: `BIZ` or `TECH`
- `NUMBER`: sequential within domain+type, zero-padded to 3 digits

Keep a registry (e.g. `error-codes.md` per domain, or a shared enum) so codes aren't reused with different meanings. This code is what support/on-call greps for — it should be stable and unique.

## What each type does differently

| Behavior | BusinessException | TechnicalException |
|---|---|---|
| Log level | WARN | ERROR |
| Stack trace logged? | No (message + error code only) | Yes, full stack trace |
| Alerting | None (unless volume-based anomaly) | Immediate page/alert |
| Retry | Not retried — it will fail the same way again | Retried per policy below |
| HTTP status (if REST) | 400/409/422 range | 500/503 range |
| Audit log | Yes, as a normal business event | Yes, as an incident |

## Retry policy for TechnicalException

| Subtype | Retry? | Strategy |
|---|---|---|
| `ExternalServiceException` | Yes | Exponential backoff, max 3 attempts, then dead-letter |
| `DataAccessFailureException` | Yes, if transient (timeout, deadlock) | 2 retries, immediate; if connection pool exhausted, no retry — alert instead |
| `MessageProcessingException` | Yes | Route to retry queue with backoff; after N failures, dead-letter queue + alert |
| `ConfigurationException` | No | Fails fast — this won't resolve itself, needs a human |

Implement retry with a circuit breaker (Resilience4j `@Retry` + `@CircuitBreaker`) rather than manual loops, so a struggling downstream system doesn't get hammered.

## Base class skeleton

```java
public abstract class BusinessException extends RuntimeException {
    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}

public abstract class TechnicalException extends RuntimeException {
    private final String errorCode;

    protected TechnicalException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
```

Concrete subclasses just supply a fixed error code and a constructor with the specific context:

```java
public class InsufficientBalanceException extends BusinessException {
    public InsufficientBalanceException(String accountId, BigDecimal requested, BigDecimal available) {
        super("PAYMENT-BIZ-002",
              String.format("Account %s: requested %s exceeds available %s", accountId, requested, available));
    }
}
```

## Self-learning note

To evolve this toward genuinely "self-learning" classification over time: log every exception occurrence with its full context (error code, stack trace hash, upstream caller) to a structured store. Periodically review `TechnicalException`s that recur with identical stack traces and high frequency — these are candidates for either a code fix or reclassification (e.g., an "expected" downstream timeout might deserve its own `BusinessException` subtype instead of paging someone every time). This is a manual review process to start; only automate the classification once you have enough labeled history to trust a model over the rule-based default.
