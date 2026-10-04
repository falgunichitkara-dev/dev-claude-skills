package com.company.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive fields and scrubs PII before a payload is written to a log
 * line. ALWAYS run payloads through this before logging — never log a raw
 * request/response/DTO object directly, and never call payload.toString() in
 * a log statement.
 *
 * Three layers (see references/pii-ugc-policy.md for full rationale):
 *   1. ALLOWLIST — only @Loggable fields are serialized; everything else is
 *      redacted by default. This is the default mode.
 *   2. UGC METADATA — known free-text / user-generated-content field names are
 *      never logged by value, even if accidentally marked @Loggable; only
 *      length + hash is logged.
 *   3. REGEX SCRUBBING — a final pass over every string value (allowlisted or
 *      not) strips emails, phone numbers, SSNs, and card numbers as a safety
 *      net against PII landing in a field nobody expected it in.
 */
@Component
public class PayloadMasker {

    public enum Mode { ALLOWLIST, BLOCKLIST }

    // Layer 2: field names treated as user-generated content — value never logged, only metadata.
    private static final Set<String> UGC_FIELDS = Set.of(
            "comment", "comments", "message", "bio", "description", "notes", "note",
            "review", "feedback", "chatmessage", "ticketbody", "caption", "post", "reply",
            "deliveryinstructions", "freetext"
    );

    // Blocklist mode only — used for low-risk internal payloads, not user-facing DTOs.
    private static final Set<String> BLOCKLIST_FIELDS = Set.of(
            "password", "token", "apikey", "secret", "authorization",
            "ssn", "taxid", "nationalid",
            "cardnumber", "cvv", "pin",
            "accesstoken", "refreshtoken"
    );

    // Layer 3: regex scrubbing patterns, applied to every string value regardless of mode.
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    private static final int MAX_FIELD_LENGTH = 2000;

    private final ObjectMapper objectMapper;

    public PayloadMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Default entry point — allowlist mode, safe for any payload that may touch user data. */
    public String mask(Object payload) {
        return mask(payload, Mode.ALLOWLIST);
    }

    public String mask(Object payload, Mode mode) {
        if (payload == null) return "null";
        try {
            Map<String, Object> result = toSafeMap(payload, mode);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            // Never let logging itself break the request — fail closed, not open.
            return "[unloggable payload: " + e.getClass().getSimpleName() + "]";
        }
    }

    private Map<String, Object> toSafeMap(Object payload, Mode mode) throws IllegalAccessException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : payload.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            String name = field.getName();
            String lowerName = name.toLowerCase();
            Object value = field.get(payload);
            if (value == null) continue;

            // Layer 2: UGC fields never log their value, regardless of mode or annotation.
            if (UGC_FIELDS.contains(lowerName)) {
                String str = String.valueOf(value);
                out.put(name, Map.of("length", str.length(), "sha256", DigestUtils.sha256Hex(str)));
                continue;
            }

            boolean include = (mode == Mode.ALLOWLIST)
                    ? field.isAnnotationPresent(Loggable.class)
                    : !BLOCKLIST_FIELDS.contains(lowerName);

            if (!include) {
                out.put(name, "***REDACTED***");
                continue;
            }

            out.put(name, scrub(truncate(value)));
        }
        return out;
    }

    private Object truncate(Object value) {
        if (value instanceof String s && s.length() > MAX_FIELD_LENGTH) {
            return s.substring(0, MAX_FIELD_LENGTH) + "...[truncated, " + s.length() + " chars total]";
        }
        return value;
    }

    /** Layer 3: regex scrub applied to every string value that made it through layers 1 and 2. */
    private Object scrub(Object value) {
        if (!(value instanceof String s)) return value;
        s = replaceAll(s, EMAIL, "***EMAIL***");
        s = replaceAll(s, SSN, "***SSN***");
        s = replaceAll(s, CARD, "***CARD***");
        s = replaceAll(s, PHONE, "***PHONE***");
        return s;
    }

    private String replaceAll(String input, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll(Matcher.quoteReplacement(replacement));
    }
}
