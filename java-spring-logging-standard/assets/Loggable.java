package com.company.common.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly marks a DTO field as safe to include when PayloadMasker serializes
 * an object in ALLOWLIST mode (the default for any payload that may touch user
 * data — see references/pii-ugc-policy.md).
 *
 * Only mark a field @Loggable if you can state in one sentence why it can never
 * hold PII or free text — e.g. an ID, enum, amount, status, or timestamp.
 * Never mark a free-text, name, contact-detail, or user-generated-content field
 * @Loggable — those are handled separately via UGC metadata logging (hash+length),
 * not by allowlisting.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Loggable {
}
