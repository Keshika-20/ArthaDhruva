package com.arthadhruva.riskengine.governance;

public record CalibrationMetrics(
        double meanPredictedProbability,
        double observedDefaultRate,
        double observedPrepayRate,
        Double calibrationRatio,
        Double brierScore,
        Double auc,
        Double ks,
        Double hosmerLemeshow
) {}
