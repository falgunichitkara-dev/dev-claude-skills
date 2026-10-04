# Logging Standards — Full Conventions

## Log levels: what goes where

| Level | Use for | Example |
|---|---|---|
| ERROR | Technical failures, unhandled exceptions, anything that pages someone | DB connection lost, NPE, external API 500 |
| WARN | Business exceptions, degraded but recoverable state | Insufficient balance, retry attempt N of 3, deprecated API usage |
| INFO | Key business events, entry/exit of major flows (via `@LogExecution`) | Order created, payment processed, message published |
| DEBUG | Detailed flow for troubleshooting, not on by default in prod | Intermediate calculation values, branch taken in logic |
| TRACE | Very fine-grained, rarely used | Raw request/response payloads (only if no PII) |

Default production level: INFO. Enable DEBUG per-package at runtime via Actuator when investigating an issue — never leave DEBUG on globally in prod.

## Message format

Always parameterized, never string concatenation or `String.format` inside the log call:

```java
log.info("Order {} created for customer {} with total {}", orderId, customerId, total);
```

Include enough context to search for this event later without needing a stack trace: entity IDs, not just entity names. A useful log line answers "what happened, to what, and can I find related log lines" — correlation ID handles the "related lines" part automatically via MDC, so you don't need to repeat it manually in the message.

## What NOT to log

- Passwords, tokens, API keys, session IDs
- Full credit card numbers, SSNs, or other PII — mask if partial info is needed (e.g., last 4 digits)
- Full request/response bodies at INFO level if they may contain PII — TRACE only, and only in non-prod
- Stack traces at WARN level for expected business exceptions (just the message + error code is enough)

## Correlation ID propagation for outbound calls

### RestTemplate
```java
@Bean
public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getInterceptors().add((request, body, execution) -> {
        request.getHeaders().add("X-Correlation-Id", MDC.get("correlationId"));
        return execution.execute(request, body);
    });
    return restTemplate;
}
```

### WebClient
```java
@Bean
public WebClient webClient() {
    return WebClient.builder()
        .filter((request, next) -> {
            ClientRequest filtered = ClientRequest.from(request)
                .header("X-Correlation-Id", MDC.get("correlationId"))
                .build();
            return next.exchange(filtered);
        })
        .build();
}
```

### Kafka producer
Add correlation ID as a message header, not a field in the payload body:
```java
ProducerRecord<String, Object> record = new ProducerRecord<>(topic, payload);
record.headers().add("correlationId", MDC.get("correlationId").getBytes(StandardCharsets.UTF_8));
```

### Kafka consumer
Extract it back into MDC at the start of message processing (in a `@KafkaListener` wrapper or interceptor), so downstream logs in that consumer thread carry it too. Clear MDC after processing to avoid leaking into the next message on a reused thread.

## Async / thread pool considerations

MDC is thread-local — it does NOT propagate automatically to `@Async` methods, `CompletableFuture`, or executor thread pools. When crossing a thread boundary, wrap the MDC context:

```java
Map<String, String> contextMap = MDC.getCopyOfContextMap();
executor.submit(() -> {
    if (contextMap != null) MDC.setContextMap(contextMap);
    try {
        // do work
    } finally {
        MDC.clear();
    }
});
```

Flag this explicitly any time you write `@Async`, `CompletableFuture.supplyAsync`, or a custom `ExecutorService` call — it's the most common place correlation IDs silently get dropped.
