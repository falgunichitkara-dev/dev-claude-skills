# java-spring-logging-standard

A Claude Skill that enforces a standardized logging, tracing, error-classification, and PII-safe payload-logging pattern across Java/Spring Boot backend code.

## What this enforces

1. **SLF4J logging** — never `System.out`, always parameterized
2. **Entry/exit tracing** via `@LogExecution` + AOP — no hand-written trace lines
3. **Correlation ID propagation** via MDC + servlet filter — automatic, never a manual parameter
4. **Typed exceptions** — `BusinessException` vs `TechnicalException`, each with distinct logging/alerting/retry behavior ("Error Hospital" pattern)
5. **Runtime-adjustable log levels** via Spring Boot Actuator — no redeploy needed
6. **Payload logging at the important places** (API boundaries, external calls, queues) — fail-safe captured on exception regardless of configured level
7. **No PII/UGC in logs, ever** — allowlist-based field logging (`@Loggable`), user-generated content logged as hash+length only, regex scrubbing as a backstop
8. **Splunk-ready output** — structured JSON to stdout, consistent field naming for cross-service search

## Structure

```
SKILL.md                           # Main skill definition — when/how this applies
references/
  logging-standards.md             # Log levels, message format, outbound propagation
  exception-hierarchy.md           # BusinessException/TechnicalException design, retry policy
  payload-logging.md               # Where to capture payloads, masking, truncation
  pii-ugc-policy.md                # Allowlist policy, UGC handling, regex scrubbing
  splunk-integration.md            # Shipping pattern, sourcetype config, field conventions
assets/
  LogExecution.java                # Annotation for entry/exit + payload tracing
  LoggingAspect.java                # AOP aspect powering @LogExecution
  CorrelationIdFilter.java          # MDC correlation ID servlet filter
  GlobalExceptionHandler.java       # Centralized exception → HTTP response mapping
  PayloadMasker.java                 # Allowlist masking, UGC metadata, regex PII scrubbing
  Loggable.java                      # Field annotation for allowlist opt-in
  logback-spring.xml                 # Structured JSON logging config
  exceptions/
    BusinessException.java
    TechnicalException.java
```

## Using this as a Claude Skill

Package the contents of this repo as a `.skill` file (zip the folder, excluding this README and `.git`) and add it to your Claude setup, or point Claude at this repo directly when working in a Java/Spring codebase.

## Using the templates directly (without Claude)

Copy the files under `assets/` into your project (adjust the `com.company.common.*` package names to your own), and follow the conventions in `references/` as your team's logging standard / PR checklist.

## Contributing

Changes to the masking rules, UGC field list, or regex patterns in `pii-ugc-policy.md` and `PayloadMasker.java` should be reviewed by someone who owns your data classification/privacy policy, not just engineering — these are compliance-relevant.
