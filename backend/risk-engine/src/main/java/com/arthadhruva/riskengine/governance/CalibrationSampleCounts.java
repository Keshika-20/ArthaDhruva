package com.arthadhruva.riskengine.governance;

public record CalibrationSampleCounts(
        int totalScored,
        int evaluated,
        int missingOutcomes,
        int defaults,
        int prepayments
) {}
