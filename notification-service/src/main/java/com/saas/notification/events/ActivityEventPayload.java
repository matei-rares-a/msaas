package com.saas.notification.events;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.saas.shared.events.EventPayload;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The payload of any audited event, as the loose set of fields it arrived with: the journal does not
 * know the producers' payload classes (no dependency between services, REQ-DATA-10). Which of these
 * fields count is decided by {@link com.saas.shared.events.ActivityEventCatalog}, not by this class.
 */
public class ActivityEventPayload implements EventPayload {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    @JsonAnySetter
    void put(String name, Object value) {
        fields.put(name, value);
    }

    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }
}
