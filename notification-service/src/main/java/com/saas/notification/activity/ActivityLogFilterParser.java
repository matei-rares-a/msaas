package com.saas.notification.activity;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns the filter a client sends (GraphQL argument or export request body) into a validated
 * {@link ActivityLogQuery} and back into its canonical map form (REQ-MON-04). Rejects, with
 * {@link IllegalArgumentException}, anything that is not exactly the documented filter - there is no
 * way to name a tenant here.
 */
public final class ActivityLogFilterParser {

    public static final Set<String> FIELDS = Set.of("from", "to", "eventTypes", "actorUserId");

    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z0-9_]{1,100}$");

    private ActivityLogFilterParser() {
    }

    /** @param filter null means no filter at all; keys other than {@link #FIELDS} are refused only when {@code strict} */
    public static ActivityLogQuery parse(Map<String, Object> filter, boolean strict) {
        if (filter == null) {
            return ActivityLogQuery.none();
        }
        if (strict) {
            for (String key : filter.keySet()) {
                if (!FIELDS.contains(key)) {
                    throw new IllegalArgumentException("unknown filter field: " + printable(key));
                }
            }
        }
        Instant from = instant(filter.get("from"), "from");
        Instant to = instant(filter.get("to"), "to");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        List<String> types = eventTypes(filter.get("eventTypes"));
        UUID actor = null;
        if (filter.get("actorUserId") != null) {
            try {
                actor = UUID.fromString(text(filter.get("actorUserId"), "actorUserId"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("actorUserId must be a UUID");
            }
        }
        return new ActivityLogQuery(from, to, types, actor);
    }

    /** The same filter as a plain map, so it can be stored and parsed again later. */
    public static Map<String, Object> toMap(ActivityLogQuery query) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (query.from() != null) {
            map.put("from", query.from().toString());
        }
        if (query.to() != null) {
            map.put("to", query.to().toString());
        }
        if (!query.eventTypes().isEmpty()) {
            map.put("eventTypes", query.eventTypes());
        }
        if (query.actorUserId() != null) {
            map.put("actorUserId", query.actorUserId().toString());
        }
        return map;
    }

    private static List<String> eventTypes(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("eventTypes must be a list");
        }
        if (list.size() > ActivityLogQuery.MAX_EVENT_TYPES) {
            throw new IllegalArgumentException("at most " + ActivityLogQuery.MAX_EVENT_TYPES + " event types can be filtered at once");
        }
        List<String> types = new ArrayList<>();
        for (Object item : list) {
            String type = text(item, "eventTypes");
            if (!EVENT_TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("not a valid event type: " + printable(type));
            }
            types.add(type);
        }
        return types;
    }

    private static Instant instant(Object value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(text(value, name));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 instant such as 2026-11-02T10:00:00Z");
        }
    }

    private static String text(Object value, String name) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(name + " must be text");
    }

    private static String printable(String value) {
        String ascii = value.replaceAll("[^\\x20-\\x7E]", "?");
        return ascii.length() > 100 ? ascii.substring(0, 100) : ascii;
    }
}
