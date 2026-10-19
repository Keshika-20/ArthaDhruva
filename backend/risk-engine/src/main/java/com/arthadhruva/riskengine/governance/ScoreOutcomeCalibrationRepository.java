package com.arthadhruva.riskengine.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code score_outcome_calibration_run}.
 * Tracks scheduled and on-demand calibration runs and stores aggregated calibration metrics.
 */
@Repository
public class ScoreOutcomeCalibrationRepository {

    private static final Logger log = LoggerFactory.getLogger(ScoreOutcomeCalibrationRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScoreOutcomeCalibrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new calibration run.
     *
     * @return true if inserted, false if a run is already active (QUEUED or RUNNING) for this tenant and model
     */
    public boolean insert(CalibrationReport run) {
        String sql = """
            INSERT INTO score_outcome_calibration_run (
                id, tenant_id, model_version, status, data_source, data_source_available,
                evaluation_window_start, evaluation_window_end, total_loans_scored,
                evaluated_samples, missing_outcomes, default_count, prepay_count,
                attempt, failure_reason, metrics, queued_at, started_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT DO NOTHING
        """;

        String metricsJson = serializeMetrics(run.metrics(), run.deciles());

        try {
            int rows = jdbc.update(sql,
                    run.id(),
                    run.tenantId(),
                    run.modelVersion(),
                    run.status(),
                    run.dataSource(),
                    run.dataSourceAvailable(),
                    run.evaluationWindow() != null && run.evaluationWindow().start() != null ? Timestamp.from(run.evaluationWindow().start()) : null,
                    run.evaluationWindow() != null && run.evaluationWindow().end() != null ? Timestamp.from(run.evaluationWindow().end()) : null,
                    run.sampleCounts() != null ? run.sampleCounts().totalScored() : 0,
                    run.sampleCounts() != null ? run.sampleCounts().evaluated() : 0,
                    run.sampleCounts() != null ? run.sampleCounts().missingOutcomes() : 0,
                    run.sampleCounts() != null ? run.sampleCounts().defaults() : 0,
                    run.sampleCounts() != null ? run.sampleCounts().prepayments() : 0,
                    1,
                    run.failureReason(),
                    metricsJson,
                    Timestamp.from(run.queuedAt() != null ? run.queuedAt() : Instant.now()),
                    run.startedAt() != null ? Timestamp.from(run.startedAt()) : null,
                    run.finishedAt() != null ? Timestamp.from(run.finishedAt()) : null
            );
            return rows > 0;
        } catch (DuplicateKeyException e) {
            log.info("Active calibration run already exists for tenant {} model {}", run.tenantId(), run.modelVersion());
            return false;
        }
    }

    public void markRunning(UUID id, Long tenantId, Instant startedAt) {
        jdbc.update("UPDATE score_outcome_calibration_run SET status = 'RUNNING', started_at = ? WHERE id = ? AND tenant_id = ?",
                Timestamp.from(startedAt), id, tenantId);
    }

    public void markCompleted(UUID id, Long tenantId, Instant finishedAt, EvaluationWindow window,
                              CalibrationSampleCounts counts, CalibrationMetrics metrics, List<CalibrationDecile> deciles) {
        String metricsJson = serializeMetrics(metrics, deciles);
        jdbc.update("""
            UPDATE score_outcome_calibration_run SET
                status = 'COMPLETED',
                finished_at = ?,
                evaluation_window_start = ?,
                evaluation_window_end = ?,
                total_loans_scored = ?,
                evaluated_samples = ?,
                missing_outcomes = ?,
                default_count = ?,
                prepay_count = ?,
                metrics = ?::jsonb
            WHERE id = ? AND tenant_id = ?
        """,
                Timestamp.from(finishedAt),
                window != null && window.start() != null ? Timestamp.from(window.start()) : null,
                window != null && window.end() != null ? Timestamp.from(window.end()) : null,
                counts.totalScored(),
                counts.evaluated(),
                counts.missingOutcomes(),
                counts.defaults(),
                counts.prepayments(),
                metricsJson,
                id,
                tenantId
        );
    }

    public void markFailed(UUID id, Long tenantId, Instant finishedAt, String reason) {
        jdbc.update("UPDATE score_outcome_calibration_run SET status = 'FAILED', finished_at = ?, failure_reason = ? WHERE id = ? AND tenant_id = ?",
                Timestamp.from(finishedAt), reason, id, tenantId);
    }

    public boolean hasActiveRun(Long tenantId, String modelVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM score_outcome_calibration_run WHERE tenant_id = ? AND model_version = ? AND status IN ('QUEUED', 'RUNNING')",
                Integer.class, tenantId, modelVersion);
        return count != null && count > 0;
    }

    public Optional<CalibrationReport> findLatestByTenantAndModel(Long tenantId, String modelVersion) {
        String sql = """
            SELECT * FROM score_outcome_calibration_run
            WHERE tenant_id = ? AND model_version = ?
            ORDER BY queued_at DESC LIMIT 1
        """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new ReportRowMapper(), tenantId, modelVersion));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<CalibrationReport> findLatestByTenant(Long tenantId) {
        String sql = """
            SELECT * FROM score_outcome_calibration_run
            WHERE tenant_id = ?
            ORDER BY queued_at DESC LIMIT 1
        """;
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new ReportRowMapper(), tenantId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CalibrationReport> findAllLatestByTenant(Long tenantId) {
        String sql = """
            SELECT DISTINCT ON (model_version) *
            FROM score_outcome_calibration_run
            WHERE tenant_id = ?
            ORDER BY model_version, queued_at DESC
        """;
        return jdbc.query(sql, new ReportRowMapper(), tenantId);
    }

    public Optional<CalibrationReport> findById(UUID id) {
        String sql = "SELECT * FROM score_outcome_calibration_run WHERE id = ?";
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, new ReportRowMapper(), id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String serializeMetrics(CalibrationMetrics metrics, List<CalibrationDecile> deciles) {
        if (metrics == null && (deciles == null || deciles.isEmpty())) {
            return null;
        }
        ObjectNode root = mapper.createObjectNode();
        if (metrics != null) {
            root.put("meanPredictedProbability", metrics.meanPredictedProbability());
            root.put("observedDefaultRate", metrics.observedDefaultRate());
            root.put("observedPrepayRate", metrics.observedPrepayRate());
            if (metrics.calibrationRatio() != null) root.put("calibrationRatio", metrics.calibrationRatio());
            if (metrics.brierScore() != null) root.put("brierScore", metrics.brierScore());
            if (metrics.auc() != null) root.put("auc", metrics.auc());
            if (metrics.ks() != null) root.put("ks", metrics.ks());
            if (metrics.hosmerLemeshow() != null) root.put("hosmerLemeshow", metrics.hosmerLemeshow());
        }
        if (deciles != null && !deciles.isEmpty()) {
            root.set("deciles", mapper.valueToTree(deciles));
        }
        return root.toString();
    }

    private class ReportRowMapper implements RowMapper<CalibrationReport> {
        @Override
        public CalibrationReport mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            Long tenantId = rs.getLong("tenant_id");
            String modelVersion = rs.getString("model_version");
            String status = rs.getString("status");
            String dataSource = rs.getString("data_source");
            boolean dataSourceAvailable = rs.getBoolean("data_source_available");

            Timestamp winStart = rs.getTimestamp("evaluation_window_start");
            Timestamp winEnd = rs.getTimestamp("evaluation_window_end");
            EvaluationWindow window = (winStart != null || winEnd != null)
                    ? new EvaluationWindow(winStart != null ? winStart.toInstant() : null, winEnd != null ? winEnd.toInstant() : null)
                    : null;

            int totalScored = rs.getInt("total_loans_scored");
            int evaluated = rs.getInt("evaluated_samples");
            int missing = rs.getInt("missing_outcomes");
            int defaults = rs.getInt("default_count");
            int prepay = rs.getInt("prepay_count");
            CalibrationSampleCounts counts = new CalibrationSampleCounts(totalScored, evaluated, missing, defaults, prepay);

            Timestamp qAt = rs.getTimestamp("queued_at");
            Timestamp sAt = rs.getTimestamp("started_at");
            Timestamp fAt = rs.getTimestamp("finished_at");
            String failureReason = rs.getString("failure_reason");

            CalibrationMetrics metrics = null;
            List<CalibrationDecile> deciles = Collections.emptyList();
            String metricsJson = rs.getString("metrics");
            if (metricsJson != null && !metricsJson.isBlank()) {
                try {
                    JsonNode node = mapper.readTree(metricsJson);
                    metrics = new CalibrationMetrics(
                            node.path("meanPredictedProbability").asDouble(0.0),
                            node.path("observedDefaultRate").asDouble(0.0),
                            node.path("observedPrepayRate").asDouble(0.0),
                            node.hasNonNull("calibrationRatio") ? node.get("calibrationRatio").asDouble() : null,
                            node.hasNonNull("brierScore") ? node.get("brierScore").asDouble() : null,
                            node.hasNonNull("auc") ? node.get("auc").asDouble() : null,
                            node.hasNonNull("ks") ? node.get("ks").asDouble() : null,
                            node.hasNonNull("hosmerLemeshow") ? node.get("hosmerLemeshow").asDouble() : null
                    );
                    if (node.has("deciles") && node.get("deciles").isArray()) {
                        deciles = mapper.readValue(node.get("deciles").toString(), new TypeReference<List<CalibrationDecile>>() {});
                    }
                } catch (Exception e) {
                    log.warn("Could not deserialize calibration metrics JSON for run {}: {}", id, e.getMessage());
                }
            }

            String message;
            if (!dataSourceAvailable || "OUTCOMES_UNAVAILABLE".equals(status)) {
                message = "Servicing feed is not configured (Design Decision 4). Realized loan outcomes are unavailable in this deployment.";
            } else if ("COMPLETED".equals(status)) {
                message = "Calibration evaluated against genuine realized outcomes.";
            } else if ("FAILED".equals(status)) {
                message = "Calibration job failed: " + failureReason;
            } else if ("QUEUED".equals(status) || "RUNNING".equals(status)) {
                message = "Calibration computation in progress.";
            } else {
                message = "No evaluation performed.";
            }

            return new CalibrationReport(
                    id, tenantId, modelVersion, status, dataSource, dataSourceAvailable, message,
                    window, counts, metrics, deciles,
                    qAt != null ? qAt.toInstant() : null,
                    sAt != null ? sAt.toInstant() : null,
                    fAt != null ? fAt.toInstant() : null,
                    failureReason
            );
        }
    }
}
