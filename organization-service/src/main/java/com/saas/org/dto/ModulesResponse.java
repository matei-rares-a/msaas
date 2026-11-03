package com.saas.org.dto;

import java.util.Map;

/** Which business modules are on for the organization, and its time zone (REQ-MOD-01). */
public record ModulesResponse(Map<String, Boolean> modules, String timezone) {
}
