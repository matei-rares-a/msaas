package com.saas.shared.events;

/**
 * Marker for every Kafka/RabbitMQ event or command payload. Implementors carry identifiers only,
 * never personal data such as a name, email or phone number (REQ-EVT-02): a Kafka event survives for
 * the retention period and must not conflict with offboarding's anonymisation. Enforced by
 * {@code EventArchRules.payloadsCarryNoPersonalData()} wherever payload classes are declared.
 */
public interface EventPayload {
}
