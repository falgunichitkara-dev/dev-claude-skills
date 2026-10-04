---
name: java-spring-logging-standard
description: Enforces a standardized logging, tracing, and error-classification pattern for Java/Spring Boot backend code. Use this skill whenever writing, editing, reviewing, or generating Java/Spring backend code — controllers, services, repositories, exception handlers, Kafka/queue listeners, or any class with business logic — even if the user doesn't explicitly ask for logging. Also use when the user mentions logging, tracing, correlation IDs, error handling, observability, instrumentation, MDC, log levels, or debugging production issues in a Java/Spring codebase. Applies to both new code and edits to existing code.
---

# Java/Spring Logging Standard

This skill makes every piece of Java/Spring backend code Claude touches follow one consistent logging and error-handling pattern, so logs are traceable, searchable, and classifiable across the whole codebase — not ad hoc per developer.

## When to apply this

Apply automatically whenever:
- Creating a new Controller, Service, Repository, Component, or message listener class
- Editing an existing class that lacks logging or has inconsistent logging
- Adding exception handling anywhere
- The user asks for "logging", "tracing", "observability", "error handling", or mentions debugging a production issue

Do NOT apply to: DTOs/POJOs, pure config classes, test files (unless the user is testing the logging itself).

## The six things every backend class needs

1. **A logger instance** — SLF4J, never `System.out`
2. **Entry/exit tracing** — via the `@LogExecution` annotation + AOP, not manual log lines in every method
3. **Correlation ID propagation** — automatic via MDC + filter, never manually passed as a parameter
4. **Typed exceptions** — every thrown exception is either `BusinessException` or `TechnicalException`, never a raw `RuntimeException`
5. **Runtime-adjustable log level** — use standard `logging.level.<package>` config, never hardcode log level checks
6. **Payload capture at the important places** — masked request/response bodies at API boundaries, external calls, and message queues, so a failure can actually be debugged after the fact — not just "the method ran"

## Quick reference

### 1. Logger declaration
Every class gets:
```java
private static final Logger log = LoggerFactory.getLogger(ClassName.class);
```
Use SLF4J parameterized logging always — never string concatenation:
```java
log.info("Processing order {} for customer {}", orderId, customerId); // correct
log.info("Processing order " + orderId); // WRONG — never do this
```

### 2. Entry/exit logging — use the annotation, don't hand-write it
Annotate any method worth tracing (controller endpoints, service entry points, listeners):
```java
@LogExecution
public OrderResponse createOrder(OrderRequest request) { ... }
```
This is handled by a single `LoggingAspect` (see `assets/LoggingAspect.java`) that logs method entry, exit, duration, and exceptions — so individual methods stay clean. Never manually add `log.info("Entering method X")` — always use the annotation instead.

### 3. Correlation ID — never pass it manually
A `CorrelationIdFilter` (see `assets/CorrelationIdFilter.java`) auto-generates or extracts an `X-Correlation-Id` header on every inbound request and puts it in MDC. Logback then includes it in every log line automatically via the pattern in `assets/logback-spring.xml`.
- For outbound HTTP calls (RestTemplate/WebClient), propagate the header via an interceptor — see `references/logging-standards.md` for the snippet.
- For Kafka/queue messages, propagate it as a message header, not a payload field.
- Claude should never add `correlationId` as an explicit method parameter — it lives in MDC and is picked up automatically.

### 4. Exception classification — always pick one of two types
Read `references/exception-hierarchy.md` before writing any `throw` or `catch` block. Summary:
- **`BusinessException`** — expected, valid business-rule failures (insufficient balance, invalid state transition, duplicate record). Logged at `WARN`, no alert, often no retry.
- **`TechnicalException`** — unexpected failures (DB down, timeout, NPE, external API failure). Logged at `ERROR`, triggers alert, eligible for retry per `references/exception-hierarchy.md`'s retry table.

Never throw a bare `RuntimeException` or `Exception`. Always extend one of these two, with a specific error code (see the reference for the code format).

A single `GlobalExceptionHandler` (`@RestControllerAdvice`, see `assets/GlobalExceptionHandler.java`) catches both types and logs/responds appropriately — don't write per-controller try/catch blocks for expected error types.

### 5. Log levels — config-driven, never hardcoded
Set per-package levels in `application.yml`:
```yaml
logging:
  level:
    com.company.orders: INFO
    com.company.orders.integration: DEBUG
```
These can be changed at runtime with no redeploy via Spring Boot Actuator:
```
POST /actuator/loggers/com.company.orders
{"configuredLevel": "DEBUG"}
```
Claude should never write code like `if (log.isDebugEnabled()) checkSomeCustomFlag()` to gate logging — level control belongs entirely to config/Actuator.

### 6. Payload logging — only at the "important places", and NEVER raw PII/UGC

Read `references/payload-logging.md` before adding any payload logging. Don't log payloads everywhere — only at: inbound API request/response, outbound external calls (3rd-party APIs, payment/bank integrations), message queue publish/consume, and before/after high-dispute business transformations (pricing, tax, billing).

At those places, set `@LogExecution(logPayload = true)`:
```java
@LogExecution(logPayload = true)
public PaymentResponse callPaymentGateway(PaymentRequest request) { ... }
```
This logs masked args/result at DEBUG on success (low noise, on-demand via Actuator), and **fail-safe logs the masked payload on failure regardless of configured level** — so the one time it actually fails, the payload that caused it is captured, not just the fact that it failed.

**Hard rule, no exceptions: no PII or user-generated content ever reaches a log line.** This is stronger than "mask known-sensitive fields." Read `references/pii-ugc-policy.md` and follow it exactly whenever a DTO field will pass through `PayloadMasker`:
- Never log a payload directly (`log.info(payload.toString())`, Jackson's default serialization, etc.) — always go through `PayloadMasker`, which defaults to **allowlist mode**: only fields explicitly marked `@Loggable` are logged by value; everything else is redacted automatically.
- When writing a new DTO that will be payload-logged, mark a field `@Loggable` only if you can state in one sentence why it can never hold PII (IDs, enums, amounts, statuses, timestamps). Names, emails, phones, addresses, free-text fields are never `@Loggable`.
- Any field that's free text or user-generated (comments, messages, bios, notes, reviews, ticket bodies) is logged as length + hash, never as its actual value — this happens automatically in `PayloadMasker` for known UGC field names, but double-check any new free-text field name is covered or added to that list.
- `PayloadMasker` also runs a final regex scrub for emails/phones/SSNs/card numbers over every string value as a safety net — but treat that as a backstop, not the primary control.

## Workflow when writing/editing a class

1. Check if `LoggingAspect`, `CorrelationIdFilter`, `GlobalExceptionHandler`, `PayloadMasker`, and the exception base classes already exist in the project (search for them). If missing, offer to scaffold them from `assets/` before adding business logic.
2. Add the SLF4J logger field.
3. Annotate the primary entry-point method(s) with `@LogExecution`.
4. If this method is an API boundary, external call, or queue handler (see `references/payload-logging.md`), add `logPayload = true`.
5. For any failure path, throw a specific subclass of `BusinessException` or `TechnicalException` — never a generic exception.
6. Do not add manual entry/exit/correlation-ID/payload log lines — those are handled by the aspect, filter, and masker.
7. If the class makes an outbound call (REST, Kafka, DB), confirm correlation ID propagation is wired per `references/logging-standards.md`.

## Shipping to Splunk (or any log store)

The app should only ever write structured JSON to stdout (already handled by `assets/logback-spring.xml`). Do not add a direct Splunk SDK/HEC call inside application code — shipping is a platform concern, not an app concern. Read `references/splunk-integration.md` when:
- Setting up Splunk ingestion for the first time
- Debugging why logs aren't searchable, fields aren't extracting, or a stack trace is showing up as multiple broken events in Splunk
- Adding a new service, to confirm its field names (`correlationId`, `application`, `errorCode`, etc.) match the existing convention — a renamed field silently breaks cross-service search

## Reference files

- `references/logging-standards.md` — full conventions: log message format, what to log at each level (TRACE/DEBUG/INFO/WARN/ERROR), outbound propagation snippets, what NOT to log (PII/secrets)
- `references/exception-hierarchy.md` — full exception class hierarchy, error code format, retry rules per exception subtype
- `references/payload-logging.md` — where payload capture pays off, masking rules, truncation, fail-safe capture on exception
- `references/pii-ugc-policy.md` — the no-exceptions PII/UGC policy: allowlist-by-default, UGC-as-metadata, regex scrubbing as a backstop
- `references/splunk-integration.md` — shipping pattern (stdout → shipper → HEC), event-boundary fix, sourcetype config, consistent field names, index/retention routing
- `assets/LoggingAspect.java` — the AOP aspect powering `@LogExecution`, including fail-safe payload capture
- `assets/CorrelationIdFilter.java` — the servlet filter for MDC correlation ID
- `assets/GlobalExceptionHandler.java` — the centralized exception handler
- `assets/PayloadMasker.java` — allowlist-by-default masking, UGC-as-metadata handling, and regex PII scrubbing before any payload is logged
- `assets/Loggable.java` — annotation marking a DTO field as explicitly safe to log (allowlist opt-in)
- `assets/logback-spring.xml` — structured JSON logging config with correlation ID in the pattern
- `assets/exceptions/` — `BusinessException.java`, `TechnicalException.java` base classes

If any of these files don't yet exist in the target project, generate them from the templates in `assets/` before wiring business logic to depend on them.
