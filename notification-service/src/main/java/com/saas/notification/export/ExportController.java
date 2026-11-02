package com.saas.notification.export;

import com.saas.notification.activity.ActivityLogFilterParser;
import com.saas.notification.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * CSV export of the tenant's activity journal, a file download kept apart from the GraphQL read API
 * (REQ-MON-05, REQ-MON-08). ADMIN only, unlike reading the journal which MANAGER may also do. The caller
 * is the identity the gateway forwarded from the token; the body can only narrow the filter and has no
 * way to name a tenant.
 */
@RestController
@RequestMapping("/notifications/activity-log/exports")
@Tag(name = "Activity log export")
public class ExportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String ADMIN_ONLY = "only an ADMIN can export the activity journal";

    public record JobResponse(UUID jobId, String status, Long rowCount, java.time.Instant createdAt,
            java.time.Instant completedAt, java.time.Instant expiresAt, String statusUrl) {
    }

    private final ActivityLogExportService exportService;

    public ExportController(ActivityLogExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    @Operation(summary = "Export the journal as CSV: 200 with the file when small, 202 with a job when it runs in the background")
    public ResponseEntity<?> export(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role,
            @RequestBody(required = false) Map<String, Object> filter) {
        Caller caller = admin(userId, tenantId, role);
        ActivityLogExportService.Outcome outcome = exportService.request(caller, ActivityLogFilterParser.parse(filter, true));
        if (outcome instanceof ActivityLogExportService.Inline inline) {
            return file(inline.csv());
        }
        UUID jobId = ((ActivityLogExportService.Background) outcome).jobId();
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.LOCATION, statusUrl(jobId))
                .body(new JobResponse(jobId, ExportJob.Status.PENDING.name(), null, null, null, null, statusUrl(jobId)));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Status of a background export you requested")
    public ResponseEntity<JobResponse> status(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role,
            @PathVariable UUID jobId) {
        Caller caller = admin(userId, tenantId, role);
        ExportJob job = exportService.status(caller, jobId).orElseThrow(ActivityLogExportService.ExportNotFoundException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new JobResponse(job.id(), job.status().name(),
                job.rowCount(), job.createdAt(), job.completedAt(), job.expiresAt(), statusUrl(job.id())));
    }

    @GetMapping("/{jobId}/download")
    @Operation(summary = "Download the CSV of a finished background export you requested")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role,
            @PathVariable UUID jobId) {
        return file(exportService.download(admin(userId, tenantId, role), jobId));
    }

    private static Caller admin(String userId, String tenantId, String role) {
        Caller caller = Caller.authenticate(userId, tenantId, role);
        caller.requireRole(ADMIN_ONLY, "ADMIN");
        return caller;
    }

    private static ResponseEntity<byte[]> file(byte[] csv) {
        return ResponseEntity.ok().contentType(CSV).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"activity-log.csv\"")
                .header("X-Content-Type-Options", "nosniff").body(csv);
    }

    private static String statusUrl(UUID jobId) {
        return "/notifications/activity-log/exports/" + jobId;
    }
}
