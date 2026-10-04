# PII / UGC — No Exceptions Policy

The blocklist approach in `references/payload-logging.md` (mask fields named `password`, `ssn`, etc.) is necessary but **not sufficient** on its own. It only catches sensitive data you thought to name. It does not catch:
- A `comment`, `bio`, `notes`, or `message` field containing a user's phone number, address, or health info typed as free text
- A DTO field someone forgot to add to the blocklist
- PII embedded inside an otherwise "safe" field (e.g. an `orderNote` field with "call me at 555-0123")

To guarantee **no PII/UGC ever reaches the log store**, this skill uses three layers together. All three apply to anything going through `PayloadMasker`, with no opt-out.

## Layer 1: Allowlist, not blocklist, for any payload that may contain user data

Default posture flips from "log everything except known-bad fields" to **"log nothing except explicitly marked-safe fields."** Mark individual DTO fields `@Loggable` (see `assets/Loggable.java`) only when a human has confirmed that field can never contain PII or free text — IDs, enums, status codes, amounts, timestamps, counts.

```java
public class OrderRequest {
    @Loggable
    private String orderId;

    @Loggable
    private BigDecimal amount;

    @Loggable
    private OrderStatus status;

    // NOT @Loggable — free text, could contain anything
    private String deliveryInstructions;

    // NOT @Loggable — direct PII
    private String customerEmail;
    private String shippingAddress;
}
```

`PayloadMasker` in **allowlist mode** serializes only `@Loggable` fields; every other field is replaced with `***REDACTED***` (or, for UGC fields specifically, see Layer 2). This is the default mode going forward — see updated `assets/PayloadMasker.java`. The old blocklist-only mode is still available for low-risk internal payloads (e.g. purely numeric/enum config objects) but is no longer the default for anything touching a user-facing DTO.

**Rule for Claude when writing new DTOs**: any field holding a name, email, phone, address, free-text note, uploaded content, or anything a user typed is NOT `@Loggable` by default. Only mark a field `@Loggable` when you can state in one sentence why it can never hold PII.

## Layer 2: UGC fields never log content — log metadata instead

User-generated content (comments, reviews, messages, support ticket bodies, chat messages, uploaded file contents, free-text notes) is **never logged, allowlisted or not** — because the risk isn't the field name, it's that the content is arbitrary and user-controlled. Instead log:

```java
log.debug("Processing comment: length={} hash={}", comment.length(), DigestUtils.sha256Hex(comment));
```

This still gives you debugging value — you can confirm a specific comment was processed (by comparing hashes if you have the original), detect truncation/encoding bugs from length, and correlate via `correlationId` — without ever writing the actual user content to the log store.

Fields to treat this way by default (non-exhaustive — treat any free-text or user-uploaded field this way): `comment`, `message`, `bio`, `description`, `notes`, `review`, `feedback`, `chatMessage`, `ticketBody`, `caption`, `post`, `reply`.

## Layer 3: Regex scrubbing as a last-resort net

Even allowlisted, structured fields can accidentally contain PII patterns (e.g. someone pastes an email into a `referenceCode` field by mistake). `PayloadMasker` runs a final regex pass over every string value — allowlisted or not — before it's logged, replacing recognized patterns:

| Pattern | Replaced with |
|---|---|
| Email address | `***EMAIL***` |
| Phone number (various formats) | `***PHONE***` |
| SSN-like (`XXX-XX-XXXX`) | `***SSN***` |
| Credit card number (13–19 digits, with/without separators) | `***CARD***` |
| IP address | `***IP***` (only if your compliance posture treats IP as PII — confirm with your data policy) |

This is a safety net, not a primary control — regex can't catch a name in free text ("call John at..."), which is exactly why Layer 2 exists for free-text fields. Don't rely on Layer 3 alone for any field you know is UGC.

## What this means for the existing LogExecution / payload-logging workflow

Nothing changes in how `@LogExecution(logPayload = true)` is applied (`references/payload-logging.md` still governs *where*) — this policy changes what `PayloadMasker` does *underneath* that annotation. The aspect calls the same `payloadMasker.mask(...)` method; the masker now defaults to allowlist + UGC-metadata-only + regex scrubbing instead of blocklist-only.

## Verification

Before shipping any new payload-logging code:
1. Grep the DTO for fields WITHOUT `@Loggable` — confirm every one of them is either genuinely not needed for debugging, or intentionally routed through the UGC metadata pattern (Layer 2)
2. Run a sample payload containing a fake email/phone/SSN through `PayloadMasker.mask()` in a test and confirm none of it appears in the output
3. Never add a test that logs real production data to verify this — use synthetic PII patterns only
