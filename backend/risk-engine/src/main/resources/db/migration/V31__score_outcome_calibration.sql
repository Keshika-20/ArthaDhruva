-- V31__score_outcome_calibration.sql
-- Compares stored loan scores with realized outcomes (Issue #27).
-- Persists scheduled calibration runs and calibration report metrics per tenant and model version.

CREATE TABLE score_outcome_calibration_run (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    data_source VARCHAR(64) NOT NULL,
    data_source_available BOOLEAN NOT NULL DEFAULT FALSE,
    evaluation_window_start TIMESTAMPTZ,
    evaluation_window_end TIMESTAMPTZ,
    total_loans_scored INT NOT NULL DEFAULT 0,
    evaluated_samples INT NOT NULL DEFAULT 0,
    missing_outcomes INT NOT NULL DEFAULT 0,
    default_count INT NOT NULL DEFAULT 0,
    prepay_count INT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 1,
    failure_reason TEXT,
    metrics JSONB,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_score_outcome_calib_tenant ON score_outcome_calibration_run (tenant_id);
CREATE INDEX idx_score_outcome_calib_model ON score_outcome_calibration_run (tenant_id, model_version, queued_at DESC);

-- At most one active (QUEUED or RUNNING) calibration run per tenant and model version:
CREATE UNIQUE INDEX uq_active_calibration_run ON score_outcome_calibration_run (tenant_id, model_version)
    WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE score_outcome_calibration_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON score_outcome_calibration_run
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- Allow background worker role to read and update calibration runs
GRANT SELECT, INSERT, UPDATE ON score_outcome_calibration_run TO arthadhruva_worker;
CREATE POLICY worker_all_tenants ON score_outcome_calibration_run TO arthadhruva_worker USING (true) WITH CHECK (true);
