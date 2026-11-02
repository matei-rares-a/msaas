package com.saas.notification.export;

import com.saas.notification.activity.ActivityLogRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("REQ-MON-05")
class ActivityLogCsvTest {

    private static String csv(ActivityLogRecord... entries) {
        StringWriter out = new StringWriter();
        ActivityLogCsv csv = new ActivityLogCsv(out);
        csv.writeHeader();
        for (ActivityLogRecord entry : entries) {
            csv.writeRow(entry);
        }
        return out.toString();
    }

    @Test
    void theFileHasAHeaderAndOneCrlfTerminatedLinePerEntryWithTheJournalsFields() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        String text = csv(new ActivityLogRecord(id, "LOGIN", actor, Instant.parse("2026-11-02T10:00:00Z"), "{}"));

        assertThat(text).isEqualTo("id,event_type,actor_user_id,created_at,metadata\r\n"
                + id + ",LOGIN," + actor + ",2026-11-02T10:00:00Z,{}\r\n");
    }

    @Test
    void anEntryWithoutAnActorHasAnEmptyActorCell() {
        UUID id = UUID.randomUUID();

        String text = csv(new ActivityLogRecord(id, "SUBSCRIPTION_CHANGED", null, Instant.parse("2026-11-02T10:00:00Z"), "{}"));

        assertThat(text).endsWith(id + ",SUBSCRIPTION_CHANGED,,2026-11-02T10:00:00Z,{}\r\n");
    }

    @Test
    void jsonMetadataIsQuotedWithItsQuotesDoubledSoItStaysOneCell() {
        UUID id = UUID.randomUUID();

        String text = csv(new ActivityLogRecord(id, "MEMBER_ROLE_CHANGED", null, Instant.parse("2026-11-02T10:00:00Z"),
                "{\"previousRole\":\"USER\",\"newRole\":\"MANAGER\"}"));

        assertThat(text).endsWith(",\"{\"\"previousRole\"\":\"\"USER\"\",\"\"newRole\"\":\"\"MANAGER\"\"}\"\r\n");
    }

    @Test
    void aLineBreakInsideAValueDoesNotStartANewRecord() {
        assertThat(ActivityLogCsv.cell("line one\nline two")).isEqualTo("\"line one\nline two\"");
    }

    @ParameterizedTest(name = "a cell starting with {0} cannot run as a spreadsheet formula")
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd"})
    void aCellThatLooksLikeAFormulaIsDefusedWithALeadingApostrophe(String value) {
        assertThat(ActivityLogCsv.cell(value)).contains("'" + value).doesNotStartWith(value);
    }

    @Test
    void anOrdinaryValueIsLeftAsIs() {
        assertThat(ActivityLogCsv.cell("MEMBER_ROLE_CHANGED")).isEqualTo("MEMBER_ROLE_CHANGED");
    }
}
