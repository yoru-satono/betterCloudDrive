package com.betterclouddrive.common.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builder that keeps message interpolation values structured and JSON-safe. */
public final class MessageParams {
    private final Map<String, Object> values = new LinkedHashMap<>();

    private MessageParams() {
    }

    public static MessageParams create() {
        return new MessageParams();
    }

    public MessageParams put(String name, Object value) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid message parameter name");
        }
        if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalArgumentException("Message parameter values must be scalar JSON values");
        }
        values.put(name, value);
        return this;
    }

    public Map<String, Object> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
