package com.saas.notification.export;

import com.saas.notification.activity.ActivityLogRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Writes journal entries as RFC 4180 CSV (REQ-MON-05): comma-separated, CRLF line ends, fields with a
 * comma, quote or line break quoted. A cell that a spreadsheet would read as a formula (it starts with
 * =, +, -, @, tab or CR) gets a leading apostrophe, so an exported value cannot run as one when the
 * file is opened.
 */
final class ActivityLogCsv {

    static final String HEADER = "id,event_type,actor_user_id,created_at,metadata";

    private final Writer out;

    ActivityLogCsv(Writer out) {
        this.out = out;
    }

    void writeHeader() {
        write(HEADER + "\r\n");
    }

    void writeRow(ActivityLogRecord entry) {
        write(String.join(",",
                cell(entry.id().toString()),
                cell(entry.eventType()),
                cell(entry.actorUserId() == null ? "" : entry.actorUserId().toString()),
                cell(entry.createdAt().toString()),
                cell(entry.metadataJson())) + "\r\n");
    }

    static String cell(String value) {
        String safe = value;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            safe = "'" + value;
        }
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private void write(String text) {
        try {
            out.write(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
