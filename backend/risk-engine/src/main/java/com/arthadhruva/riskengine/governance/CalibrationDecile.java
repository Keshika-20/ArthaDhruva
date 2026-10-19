package com.arthadhruva.riskengine.governance;

public record CalibrationDecile(
        int bin,
        double pdFrom,
        double pdTo,
        int sampleCount,
        double meanPredicted,
        double observedRate,
        int defaultCount,
        int prepayCount
) {}
