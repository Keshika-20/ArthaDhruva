package com.arthadhruva.riskengine.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CalibrationReport(
        UUID id,
        Long tenantId,
        String modelVersion,
        String status,
        String dataSource,
        boolean dataSourceAvailable,
        String message,
        EvaluationWindow evaluationWindow,
        CalibrationSampleCounts sampleCounts,
        CalibrationMetrics metrics,
        List<CalibrationDecile> deciles,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason
) {}
