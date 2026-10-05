package com.saas.notification.activity;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * Keeps {@code activity_log}'s monthly partitions rolling (REQ-MON-07): the current and next month
 * always exist, and partitions entirely older than the retention window are dropped wholesale in
 * production. Partition names are deterministic ({@code activity_log_yYYYYmMM}), so the retention check
 * reads the month straight from the name instead of parsing {@code pg_get_expr} bounds.
 */
public class ActivityLogPartitionMaintenance {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ActivityLogPartitionMaintenance(DataSource dataSource, Clock clock) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /** Creates the current and next month's partitions if they don't exist yet. */
    public void ensureUpcomingPartitions() {
        YearMonth current = YearMonth.now(clock);
        createPartitionIfMissing(current);
        createPartitionIfMissing(current.plusMonths(1));
    }

    /** Drops whole partitions whose entire month is older than the retention window (production). */
    public List<String> dropPartitionsOlderThan(int retentionMonths) {
        YearMonth cutoff = YearMonth.now(clock).minusMonths(retentionMonths);
        List<String> dropped = partitionNames().stream()
                .filter(name -> monthOf(name).isBefore(cutoff))
                .toList();
        for (String partition : dropped) {
            jdbcTemplate.execute("DROP TABLE notif." + partition);
        }
        return dropped;
    }

    /** Row-level cleanup used only by the test profile, since real partitions are month-granular (REQ-MON-03). */
    public int deleteRowsOlderThan(Duration retention) {
        Instant cutoff = clock.instant().minus(retention);
        return jdbcTemplate.update("DELETE FROM notif.activity_log WHERE created_at < ?", Timestamp.from(cutoff));
    }

    public boolean partitionExists(YearMonth month) {
        return partitionNames().contains(partitionName(month));
    }

    private void createPartitionIfMissing(YearMonth month) {
        if (partitionExists(month)) {
            return;
        }
        String name = partitionName(month);
        java.time.LocalDate from = month.atDay(1);
        java.time.LocalDate to = month.plusMonths(1).atDay(1);
        jdbcTemplate.execute("CREATE TABLE notif." + name + " PARTITION OF notif.activity_log"
                + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
    }

    private List<String> partitionNames() {
        return jdbcTemplate.queryForList(
                "SELECT c.relname FROM pg_inherits i"
                        + " JOIN pg_class c ON c.oid = i.inhrelid"
                        + " JOIN pg_class p ON p.oid = i.inhparent"
                        + " JOIN pg_namespace n ON n.oid = p.relnamespace"
                        + " WHERE p.relname = 'activity_log' AND n.nspname = 'notif'",
                String.class);
    }

    private static String partitionName(YearMonth month) {
        return String.format("activity_log_y%04dm%02d", month.getYear(), month.getMonthValue());
    }

    private static YearMonth monthOf(String partitionName) {
        String suffix = partitionName.substring("activity_log_y".length());
        int year = Integer.parseInt(suffix.substring(0, 4));
        int monthValue = Integer.parseInt(suffix.substring(5, 7));
        return YearMonth.of(year, monthValue);
    }
}
