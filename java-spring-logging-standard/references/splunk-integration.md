# Splunk Integration — Making Logs Easy to Capture and Search

JSON output alone doesn't guarantee Splunk indexes it cleanly. This covers the three things that actually break in practice: event boundaries, field extraction, and timestamp alignment — plus the recommended shipping pattern.

## Recommended pattern: app → stdout → shipper → Splunk HEC

Don't push to Splunk directly from the app (no Splunk SDK dependency in your Spring Boot code). Instead:

```
App (logback → JSON on stdout) → Fluent Bit / Splunk OTel Collector → Splunk HTTP Event Collector (HEC)
```

Why this over a direct HEC appender in the app:
- **Decoupled** — switch log backends (ELK, Datadog, Splunk) without touching app code or redeploying
- **Resilient** — the shipper buffers and retries if Splunk is briefly unreachable; a direct in-app HEC call can block or lose events if the app crashes mid-send
- **Standard for containerized apps** — if you're on Kubernetes/ECS, this is the 12-factor pattern: apps just write to stdout, the platform handles the rest

The `logback-spring.xml` from this skill already logs structured JSON to stdout in non-local profiles — that's the correct app-side behavior. Nothing else needs to change in the app for this pattern.

## 1. Fix event boundaries (the #1 thing that breaks)

A multi-line stack trace naively written to a log file gets split into multiple Splunk events — one per line — which destroys searchability. The `logstash-logback-encoder` config in this skill already avoids this because it serializes the entire event, including the stack trace, into **one JSON object per line** (the stack trace becomes a single `stack_trace` string field with embedded `\n`, not raw multi-line text). Confirm this is working by checking a sample log line is valid single-line JSON before shipping it anywhere.

If using Fluent Bit, set the JSON parser's `Multiline` mode OFF and parser type `json` — since each line is already a complete JSON object, no multi-line merging is needed:

```ini
[INPUT]
    Name   tail
    Path   /var/log/containers/*.log
    Parser json
    Tag    app.*

[OUTPUT]
    Name   splunk
    Match  app.*
    Host   <splunk-hec-host>
    Port   8088
    Splunk_Token <HEC-token>
    Splunk_Send_Raw Off
```

## 2. Define a sourcetype so Splunk parses JSON correctly, not line-by-line

Without an explicit sourcetype, Splunk may guess the format wrong (especially mixing this app's logs with others in a shared index). Define one in `props.conf` on the Splunk side (or via the Fluent Bit output's `splunk_sourcetype` field):

```ini
[your_app:json]
KV_MODE = json
SHOULD_LINEMERGE = false
LINE_BREAKER = ([\r\n]+)
TIME_PREFIX = "timestamp":"
TIME_FORMAT = %Y-%m-%dT%H:%M:%S.%3N%z
MAX_TIMESTAMP_LOOKAHEAD = 32
TRUNCATE = 100000
```

- `SHOULD_LINEMERGE = false` — each line is already a full event, don't try to merge
- `KV_MODE = json` — auto-extract every JSON field as a searchable field (so `correlationId`, `errorCode`, `level` etc. are all queryable without extra config)
- `TIME_PREFIX`/`TIME_FORMAT` — tells Splunk exactly where the timestamp is and its format, so it indexes on your app's event time, not on ingestion time (critical — otherwise event order and time-range searches get unreliable under any ingestion delay)

## 3. Keep field names consistent across every service

`KV_MODE = json` auto-extracts whatever field names are present — which means if Service A logs `correlationId` and Service B logs `correlation_id`, you cannot write one search that spans both. Fix this once, in `logback-spring.xml`'s `customFields` and the MDC key names (already standardized in this skill as `correlationId`, `application`, `errorCode`), and never let individual teams rename them.

Minimum field set every service must emit (already covered by the skill's `logback-spring.xml` + `CorrelationIdFilter`):

| Field | Source | Why Splunk needs it |
|---|---|---|
| `timestamp` | logstash encoder | event time, not ingestion time |
| `level` | logstash encoder | filter by severity |
| `correlationId` | MDC, via `CorrelationIdFilter` | stitch one request across services — this is the single most useful Splunk search field for debugging |
| `application` | `logback-spring.xml` customFields | filter/split by service in a shared index |
| `logger_name` | logstash encoder | locate the originating class |
| `errorCode` | logged explicitly in `GlobalExceptionHandler` / `BusinessException`/`TechnicalException` | search Error Hospital incidents by code, build dashboards per error type |

## 4. Index and retention

Route payload-bearing DEBUG logs (see `references/payload-logging.md`) to a **separate index** from standard INFO business-event logs, with shorter retention and tighter access control — payloads are the most sensitive content in your logs even after masking. This is a Splunk-side routing rule (`transforms.conf` + `props.conf` `TRANSFORMS-routing`), not an app change — the app just needs to emit `application` and `level` fields reliably enough to route on.

## 5. Quick sanity check before going live

1. `kubectl logs <pod>` (or equivalent) — confirm each line is valid, complete JSON (paste one into a JSON validator)
2. Confirm `correlationId` is present on every line within one request's logs, and that a search `correlationId="<id>"` returns the full request trace across services
3. Trigger a test exception and confirm the stack trace appears as a single field, not dozens of broken events
4. Confirm Splunk's indexed `_time` matches your app's `timestamp` field, not the time the shipper happened to forward it
