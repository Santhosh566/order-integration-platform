package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;

/**
 * Reads the order version a caller saw from an If-Match header: {@code "3"}, {@code W/"3"} or {@code 3}.
 * No header, or {@code *}, means "whatever it is now", which is how partners and older callers act.
 */
public final class IfMatch {

    private IfMatch() {
    }

    public static Long version(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.equals("*")) {
            return null;
        }
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException e) {
            throw new InvalidOrderException("If-Match must be the order's version as sent in its ETag, like \"3\"");
        }
    }

    /** The ETag for an order at this version. */
    public static String etag(Long version) {
        return "\"" + (version == null ? 0 : version) + "\"";
    }
}
