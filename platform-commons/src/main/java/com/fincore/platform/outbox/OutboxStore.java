package com.fincore.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/** JDBC access to the outbox table. Joins the caller's transaction (JpaTransactionManager exposes its JDBC connection). */
public class OutboxStore {
    public record Row(long seq, UUID eventId, String topic, String key, String payload) {}

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String topic, String key, String payload) {
        jdbc.update("INSERT INTO outbox_events(event_id, topic, event_key, payload) VALUES (?,?,?,?)",
                eventId, topic, key, payload);
    }

    /** SKIP LOCKED lets several relay instances run without publishing the same row concurrently. */
    public List<Row> lockBatch(int limit) {
        return jdbc.query("""
                SELECT seq, event_id, topic, event_key, payload FROM outbox_events
                WHERE published_at IS NULL ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Row(rs.getLong("seq"), rs.getObject("event_id", UUID.class), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("payload")), limit);
    }

    public void markPublished(long seq) {
        jdbc.update("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE seq = ?", seq);
    }

    public void recordFailure(long seq, String error) {
        String trimmed = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 480));
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE seq = ?", trimmed, seq);
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }
}
