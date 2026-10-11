package com.trippilot.observability;

import java.util.*;

/** Defense in depth. Callers must also allowlist trace fields and exclude raw sensitive inputs. */
public final class TraceRedactor {
    private TraceRedactor() {}
    private static final Set<String> SENSITIVE = Set.of("password", "passwordhash", "authorization", "cookie", "setcookie", "apikey", "accesstoken", "refreshtoken", "passport", "aadhaar", "cardnumber", "cvv", "travelername", "email", "phone");

    public static Map<String, Object> redact(Map<String, ?> input, List<String> knownSecrets) {
        var output = new LinkedHashMap<String, Object>();
        input.forEach((key, value) -> {
            String normalized = key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            output.put(key, SENSITIVE.contains(normalized) ? "[REDACTED]" : safe(value, knownSecrets));
        });
        return Collections.unmodifiableMap(output);
    }

    private static Object safe(Object value, List<String> knownSecrets) {
        if (value instanceof Map<?, ?> map) {
            var strings = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> strings.put(String.valueOf(key), item));
            return redact(strings, knownSecrets);
        }
        if (value instanceof Collection<?> values) { return values.stream().map(item -> safe(item, knownSecrets)).toList(); }
        if (value instanceof String text) {
            String cleaned = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]")
                    .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]");
            for (String secret : knownSecrets) { if (secret != null && !secret.isEmpty()) { cleaned = cleaned.replace(secret, "[REDACTED]"); } }
            return cleaned;
        }
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof UUID || value instanceof Enum<?>) { return value; }
        return "[REDACTED]";
    }
}
