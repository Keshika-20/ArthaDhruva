package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.governance.outcome.FixtureOutcomeSource;
import com.arthadhruva.riskengine.governance.outcome.LoanRealizedOutcome;
import com.arthadhruva.riskengine.governance.outcome.OutcomeSource;
import com.arthadhruva.riskengine.governance.outcome.UnavailableOutcomeSource;
import com.arthadhruva.riskengine.score.LoanScoreId;
import com.arthadhruva.riskengine.score.LoanScoreRecord;
import com.arthadhruva.riskengine.score.LoanScoreRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCalibrationServiceTest {

    private LoanScoreRecordRepository loanScores;
    private ScoreOutcomeCalibrationRepository calibrationRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        loanScores = Mockito.mock(LoanScoreRecordRepository.class);
        calibrationRepo = Mockito.mock(ScoreOutcomeCalibrationRepository.class);
    }

    @Test
    void unavailableSourceExplicitlyReportsUnavailable() {
        OutcomeSource unavailableSource = new UnavailableOutcomeSource();
        ModelCalibrationService service = new ModelCalibrationService(unavailableSource, loanScores, calibrationRepo, executor);

        LoanScoreRecord r1 = new LoanScoreRecord(new LoanScoreId(1L, "L1"), 0.05, 0.04, Instant.now(), "pd_24m@2026.10.01-1308");
        when(loanScores.findByIdTenantId(1L)).thenReturn(List.of(r1));

        CalibrationReport report = service.evaluate(1L, "pd_24m");

        assertEquals("OUTCOMES_UNAVAILABLE", report.status());
        assertFalse(report.dataSourceAvailable());
        assertEquals("unconfigured", report.dataSource());
        assertTrue(report.message().contains("Design Decision 4"));
        assertEquals(1, report.sampleCounts().totalScored());
        assertEquals(0, report.sampleCounts().evaluated());
        assertEquals(0, report.sampleCounts().missingOutcomes());
        assertNull(report.metrics());
        assertTrue(report.deciles().isEmpty());

        verify(calibrationRepo).insert(any(CalibrationReport.class));
    }

    @Test
    void validFixtureCalculatesExactCalibrationMetrics() {
        Instant now = Instant.now();
        LoanScoreRecord r1 = new LoanScoreRecord(new LoanScoreId(1L, "L1"), 0.1, 0.1, now, "pd_24m@v1");
        LoanScoreRecord r2 = new LoanScoreRecord(new LoanScoreId(1L, "L2"), 0.2, 0.2, now, "pd_24m@v1");
        LoanScoreRecord r3 = new LoanScoreRecord(new LoanScoreId(1L, "L3"), 0.4, 0.4, now, "pd_24m@v1");
        LoanScoreRecord r4 = new LoanScoreRecord(new LoanScoreId(1L, "L4"), 0.8, 0.8, now, "pd_24m@v1");

        when(loanScores.findByIdTenantId(1L)).thenReturn(List.of(r1, r2, r3, r4));

        OutcomeSource fixture = FixtureOutcomeSource.fromOutcomes(List.of(
                LoanRealizedOutcome.performingOutcome("L1", now),
                LoanRealizedOutcome.performingOutcome("L2", now),
                LoanRealizedOutcome.defaultOutcome("L3", now, now),
                LoanRealizedOutcome.defaultOutcome("L4", now, now)
        ));

        when(calibrationRepo.insert(any(CalibrationReport.class))).thenReturn(true);
        when(calibrationRepo.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.of(new CalibrationReport(
                    id, 1L, "pd_24m@v1", "COMPLETED", "fixture", true,
                    "Completed", null, new CalibrationSampleCounts(4, 4, 0, 2, 0),
                    new CalibrationMetrics(0.375, 0.5, 0.0, 0.75, 0.1125, 1.0, 1.0, 0.0),
                    Collections.emptyList(), now, now, now, null
            ));
        });

        ModelCalibrationService service = new ModelCalibrationService(fixture, loanScores, calibrationRepo, executor);
        CalibrationReport report = service.evaluate(1L, "pd_24m@v1");

        assertNotNull(report);
        assertEquals("COMPLETED", report.status());
        assertEquals(4, report.sampleCounts().evaluated());
        assertEquals(2, report.sampleCounts().defaults());
        assertEquals(0, report.sampleCounts().missingOutcomes());

        // Verify mathematical helper calculations directly
        List<ModelCalibrationService.EvaluatedItem> items = List.of(
                new ModelCalibrationService.EvaluatedItem("L1", 0.1, false, false),
                new ModelCalibrationService.EvaluatedItem("L2", 0.2, false, false),
                new ModelCalibrationService.EvaluatedItem("L3", 0.4, true, false),
                new ModelCalibrationService.EvaluatedItem("L4", 0.8, true, false)
        );

        CalibrationMetrics metrics = ModelCalibrationService.computeMetrics(items, 2, 0);
        assertNotNull(metrics);
        assertEquals(0.375, metrics.meanPredictedProbability(), 1e-6);
        assertEquals(0.5, metrics.observedDefaultRate(), 1e-6);
        assertEquals(0.75, metrics.calibrationRatio(), 1e-6);
        assertEquals(0.1125, metrics.brierScore(), 1e-6);
        assertEquals(1.0, metrics.auc(), 1e-6);
        assertEquals(1.0, metrics.ks(), 1e-6);
    }

    @Test
    void missingOutcomesHandledWithCorrectDenominators() {
        Instant now = Instant.now();
        // 5 loans scored, but only 3 have recorded outcomes in the feed
        LoanScoreRecord r1 = new LoanScoreRecord(new LoanScoreId(1L, "L1"), 0.1, 0.1, now, "pd_24m@v1");
        LoanScoreRecord r2 = new LoanScoreRecord(new LoanScoreId(1L, "L2"), 0.2, 0.2, now, "pd_24m@v1");
        LoanScoreRecord r3 = new LoanScoreRecord(new LoanScoreId(1L, "L3"), 0.3, 0.3, now, "pd_24m@v1");
        LoanScoreRecord r4 = new LoanScoreRecord(new LoanScoreId(1L, "L4"), 0.4, 0.4, now, "pd_24m@v1");
        LoanScoreRecord r5 = new LoanScoreRecord(new LoanScoreId(1L, "L5"), 0.5, 0.5, now, "pd_24m@v1");

        when(loanScores.findByIdTenantId(1L)).thenReturn(List.of(r1, r2, r3, r4, r5));

        // L4 and L5 are missing from outcome feed!
        OutcomeSource fixture = FixtureOutcomeSource.fromOutcomes(List.of(
                LoanRealizedOutcome.performingOutcome("L1", now),
                LoanRealizedOutcome.defaultOutcome("L2", now, now),
                LoanRealizedOutcome.prepayOutcome("L3", now, now)
        ));

        ModelCalibrationService service = new ModelCalibrationService(fixture, loanScores, calibrationRepo, executor);
        service.evaluate(1L, "pd_24m@v1");

        // Verify markCompleted was called with sample counts having evaluated = 3, missing = 2, defaults = 1, prepay = 1
        verify(calibrationRepo).markCompleted(
                any(UUID.class),
                eq(1L),
                any(Instant.class),
                any(),
                eq(new CalibrationSampleCounts(5, 3, 2, 1, 1)),
                any(),
                any()
        );
    }

    @Test
    void singleClassEdgeCasesSafelyReturnNullAuc() {
        // All loans non-defaulted
        List<ModelCalibrationService.EvaluatedItem> noDefaults = List.of(
                new ModelCalibrationService.EvaluatedItem("L1", 0.02, false, false),
                new ModelCalibrationService.EvaluatedItem("L2", 0.05, false, false)
        );
        Double aucNoDef = ModelCalibrationService.computeAuc(noDefaults, 0);
        assertNull(aucNoDef);
        assertEquals(0.0, ModelCalibrationService.computeKs(noDefaults, 0));

        // All loans defaulted
        List<ModelCalibrationService.EvaluatedItem> allDefaults = List.of(
                new ModelCalibrationService.EvaluatedItem("L1", 0.8, true, false),
                new ModelCalibrationService.EvaluatedItem("L2", 0.9, true, false)
        );
        Double aucAllDef = ModelCalibrationService.computeAuc(allDefaults, 2);
        assertNull(aucAllDef);
        assertEquals(0.0, ModelCalibrationService.computeKs(allDefaults, 2));
    }

    @Test
    void decilesBinningCalculatesCorrectAggregates() {
        List<ModelCalibrationService.EvaluatedItem> items = List.of(
                new ModelCalibrationService.EvaluatedItem("L1", 0.01, false, false),
                new ModelCalibrationService.EvaluatedItem("L2", 0.02, false, false),
                new ModelCalibrationService.EvaluatedItem("L3", 0.05, false, true),
                new ModelCalibrationService.EvaluatedItem("L4", 0.10, true, false),
                new ModelCalibrationService.EvaluatedItem("L5", 0.20, true, false)
        );

        List<CalibrationDecile> deciles = ModelCalibrationService.computeDeciles(items);
        assertFalse(deciles.isEmpty());
        int totalInDeciles = deciles.stream().mapToInt(CalibrationDecile::sampleCount).sum();
        assertEquals(5, totalInDeciles);

        int totalDefaultsInDeciles = deciles.stream().mapToInt(CalibrationDecile::defaultCount).sum();
        assertEquals(2, totalDefaultsInDeciles);

        int totalPrepaysInDeciles = deciles.stream().mapToInt(CalibrationDecile::prepayCount).sum();
        assertEquals(1, totalPrepaysInDeciles);
    }
}
