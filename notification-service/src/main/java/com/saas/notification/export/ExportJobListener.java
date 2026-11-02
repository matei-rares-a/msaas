package com.saas.notification.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.shared.events.EventEnvelope;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Worker for export jobs too big to run inline (REQ-MON-05, REQ-SCALE-03): consumes the command the
 * outbox relay published to RabbitMQ and runs the job under the tenant named in the envelope. RabbitMQ
 * hands each command to one consumer and removes it once handled; a failure is retried and then
 * dead-lettered (REQ-EVT-07).
 */
public class ExportJobListener {

    private final ActivityLogExportService exportService;
    private final ObjectMapper objectMapper;

    public ExportJobListener(ActivityLogExportService exportService, ObjectMapper objectMapper) {
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = ExportConfig.QUEUE, containerFactory = "exportListenerContainerFactory")
    public void onCommand(String payload) throws JsonProcessingException {
        EventEnvelope<ActivityExportRequested> envelope = objectMapper.readValue(payload,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ActivityExportRequested.class));
        if (!ActivityExportRequested.EVENT_TYPE.equals(envelope.getEventType())) {
            return; // forward-compatible: other command types are not this worker's business
        }
        if (!envelope.isTenantScoped()) {
            throw new IllegalArgumentException("an export command must name a tenant");
        }
        exportService.runJob(envelope.getTenantId(), envelope.getPayload().jobId());
    }
}
