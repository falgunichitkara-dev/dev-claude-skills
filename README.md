# Claude Skills — Production-Grade Backend Standards

This repo includes **Claude Skills** that enforce our team's engineering standards automatically whenever Claude writes, edits, or reviews backend code here. They are not optional conventions documented in a wiki somewhere — they're active instructions Claude applies every time, so code quality doesn't depend on which developer (or which session) asked for it.

## Why this exists

Any backend service that's going to production needs a baseline of non-negotiables: traceable logs, classified errors, no PII leaks, consistent correlation IDs across services. Without a shared mechanism, these drift — every developer (and every AI-assisted change) does it slightly differently, and by the time you're debugging a production incident at 2am, half your services don't even agree on a field name.

These skills exist so that **any class Claude touches in this repo automatically meets that baseline**, without anyone having to remember to ask for it.

## Skills in this repo

### `java-spring-logging-standard`
Enforces a standardized logging, tracing, and error-classification pattern for all Java/Spring Boot backend code.

Applies automatically to: Controllers, Services, Repositories, Components, message/queue listeners, and any exception-handling code — new or edited.

What it guarantees on every class:
1. **SLF4J logging** — never `System.out`, always parameterized
2. **Entry/exit tracing** — via `@LogExecution` + AOP, not hand-written log lines
3. **Correlation ID propagation** — automatic via MDC, never a manual parameter
4. **Typed exceptions** — every throw is `BusinessException` or `TechnicalException`, never a raw `RuntimeException`
5. **Runtime-adjustable log levels** — via `application.yml` / Actuator, never hardcoded
6. **Masked payload capture at the boundaries that matter** — API in/out, external calls, queues — with a hard no-PII/no-UGC rule enforced by `PayloadMasker`

It also governs how logs ship to Splunk: structured JSON to stdout only, no in-app HEC calls — shipping is a platform concern.

Full details: see the skill's own docs for the exception hierarchy, payload logging rules, and PII/UGC policy.

## How it behaves on a new codebase

The first time the skill touches this repo, it checks whether the shared infrastructure (`LoggingAspect`, `CorrelationIdFilter`, `GlobalExceptionHandler`, `PayloadMasker`, the `BusinessException`/`TechnicalException` base classes) already exists. If not, it scaffolds all of it before writing any business logic, so every class from the first one onward is built on the same foundation. Every class after that just plugs into what's already there.

## For developers

You don't need to invoke this explicitly — it triggers automatically whenever backend code is created or edited in this repo. If you're reviewing an AI-assisted change and don't see typed exceptions, `@LogExecution`, or correlation ID handling, something's off — flag it.

If you're adding a new skill to this repo (e.g. for a different layer or language), document it in this README following the same format.
