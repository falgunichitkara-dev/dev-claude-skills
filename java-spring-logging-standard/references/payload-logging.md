# Payload Logging — What, Where, and How Safely

Entry/exit logging (via `@LogExecution`) tells you a method ran. It does NOT tell you *what data* it ran with — and that's usually what you actually need when debugging a production issue. This reference defines where to capture full payloads, at what level, and how to do it without leaking sensitive data or flooding the log store.

## Where to capture payloads (the important places)

Capture the full payload at exactly these points — not everywhere:

1. **Inbound API request body** — at the controller boundary, before validation/transformation
2. **Inbound API response body** — what was actually returned, especially on error responses
3. **Outbound external call request/response** — the exact payload sent to and received from third-party systems (banks, payment gateways, partner APIs) — this is usually the #1 thing support asks for
4. **Message queue payload** — on publish and on consume, for Kafka/RabbitMQ/SQS messages
5. **On exception** — the payload being processed when a `TechnicalException` or unhandled exception is thrown, regardless of configured log level (see "fail-safe capture" below)
6. **Before/after a critical business transformation** — e.g. before and after a pricing or tax calculation, if that calculation is a frequent source of disputes

Do NOT log payloads on every internal method call, every DB query result, or every intermediate variable — that's noise, not signal, and it's expensive at scale.

## What level to log payloads at

| Scenario | Level | Why |
|---|---|---|
| Normal successful inbound/outbound call | DEBUG | High volume — only turned on when actively investigating, via Actuator, per-package |
| Exception / failure path | ERROR or WARN (matches the exception type) | **Always captured, regardless of configured level** — this is the one exception to "DEBUG only" |
| External system call (inbound+outbound) | INFO, truncated/summarized; full payload at DEBUG | External call payloads are disproportionately useful for support — worth a higher default visibility, but keep it summarized unless DEBUG is on |

**Fail-safe capture rule**: when a method annotated `@LogExecution(logPayload = true)` throws, the aspect logs the input payload at the exception's log level even if DEBUG is off — otherwise the one time you need the payload (it failed) is the one time it wasn't captured. See `assets/LoggingAspect.java` v2 below.

## Masking rules — never log raw sensitive data

Before any payload is serialized into a log line, run it through `PayloadMasker` (see `assets/PayloadMasker.java`). Default masked fields (case-insensitive key match):
- `password`, `token`, `apiKey`, `secret`, `authorization`
- `ssn`, `taxId`, `nationalId`
- `cardNumber`, `cvv`, `pin` — card numbers masked to last 4 digits, not fully redacted (last 4 is usually enough for support to match a complaint to a transaction)
- `accessToken`, `refreshToken`

Extend the masked-field list per domain (e.g. add `dateOfBirth`, `email` if your compliance posture requires it) — treat the default list as a floor, not a ceiling.

## Truncation and size limits

- Truncate any single payload field to 2000 characters by default before logging; append `...[truncated, N bytes total]`
- Never log a raw binary/file payload (images, PDFs, attachments) — log metadata instead (filename, size, content-type, checksum)
- For large JSON arrays (bulk operations), log the count and first/last 2 elements, not the full array

## Structure — payload as a field, not inline text

Log the (masked, truncated) payload as a **separate structured field**, not concatenated into the message string, so it's independently searchable in ELK/Splunk:

```java
log.debug("Outbound call to payment gateway", 
    kv("payload", payloadMasker.mask(requestBody)),
    kv("endpoint", endpointUrl));
```

If using plain SLF4J without a structured-arguments library (`net.logstash.logback.argument.StructuredArguments`), at minimum keep the payload as its own parameterized argument so it lands in its own JSON field via the Logstash encoder, not merged into `message`:

```java
log.debug("Outbound call to payment gateway, payload={}", payloadMasker.mask(requestBody));
```

## Retention note

Full payloads (even masked ones) are the most sensitive thing in your logs. Confirm your log retention/archival policy treats DEBUG-level payload logs with shorter retention than standard INFO business-event logs, and that access to raw log search is restricted — this is a compliance conversation worth having with security, not just an engineering default.
