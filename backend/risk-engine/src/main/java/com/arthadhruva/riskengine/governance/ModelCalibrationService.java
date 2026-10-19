package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.governance.outcome.LoanRealizedOutcome;
import com.arthadhruva.riskengine.governance.outcome.OutcomeSource;
import com.arthadhruva.riskengine.score.LoanScoreRecord;
import com.arthadhruva.riskengine.score.LoanScoreRecordRepository;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Compares stored model scores with realized outcomes (Issue #27).
 *
 * <p>Requirements:
 * <ul>
 *   <li>Evaluates calibration using genuine observed outcomes from {@link OutcomeSource}.</li>
 *   <li>When no servicing feed is configured (Decision 4), explicitly reports outcomes as unavailable.
 *       Never synthesizes or fabricates realized outcomes in production.</li>
 *   <li>Enforces correct denominators: metrics are computed only over loans with known/observed outcomes;
 *       missing outcomes are explicitly counted and never defaulted to non-default.</li>
 *   <li>Enqueues heavy evaluation onto a background worker pool ({@code riskRunExecutor}),
 *       keeping scheduler and request threads responsive.</li>
 * </ul>
 */
@Service
public class ModelCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(ModelCalibrationService.class);
    public static final String DEFAULT_MODEL_PREFIX = "pd_24m";

    private final OutcomeSource outcomeSource;
    private final LoanScoreRecordRepository loanScoreRepository;
    private final ScoreOutcomeCalibrationRepository calibrationRepository;
    private final ExecutorService workerPool;

    public ModelCalibrationService(OutcomeSource outcomeSource,
                                   LoanScoreRecordRepository loanScoreRepository,
                                   ScoreOutcomeCalibrationRepository calibrationRepository,
                                   @Qualifier("riskRunExecutor") ExecutorService workerPool) {
        this.outcomeSource = outcomeSource;
        this.loanScoreRepository = loanScoreRepository;
        this.calibrationRepository = calibrationRepository;
        this.workerPool = workerPool;
    }

    /**
     * Obtains the latest calibration report for the tenant and model.
     * If no run has ever been persisted, an initial check is evaluated/recorded.
     */
    public CalibrationReport getLatestReport(Long tenantId, String modelIdOrVersion) {
        String version = resolveTargetVersion(tenantId, modelIdOrVersion);
        Optional<CalibrationReport> existing = calibrationRepository.findLatestByTenantAndModel(tenantId, version);
        if (existing.isPresent()) {
            return existing.get();
        }
        // If outcome source is unavailable, generate and store the baseline unavailable report immediately
        if (!outcomeSource.isAvailable()) {
            return evaluate(tenantId, version);
        }
        // Enqueue run and return queued state
        return enqueue(tenantId, version);
    }

    /**
     * Enqueues or immediately computes a calibration run for the given tenant and model.
     * Heavy computation executes asynchronously on the background worker pool.
     */
    public CalibrationReport enqueue(Long tenantId, String modelIdOrVersion) {
        String version = resolveTargetVersion(tenantId, modelIdOrVersion);

        // If outcome source is unavailable, no heavy compute needed -- record immediately
        if (!outcomeSource.isAvailable()) {
            return evaluate(tenantId, version);
        }

        UUID runId = UUID.randomUUID();
        CalibrationReport queuedReport = new CalibrationReport(
                runId,
                tenantId,
                version,
                "QUEUED",
                outcomeSource.sourceName(),
                outcomeSource.isAvailable(),
                "Calibration computation queued for background worker.",
                null,
                new CalibrationSampleCounts(0, 0, 0, 0, 0),
                null,
                Collections.emptyList(),
                Instant.now(),
                null,
                null,
                null
        );

        boolean inserted = calibrationRepository.insert(queuedReport);
        if (!inserted) {
            log.info("A calibration run is already in progress for tenant {} and model {}", tenantId, version);
            return calibrationRepository.findLatestByTenantAndModel(tenantId, version).orElse(queuedReport);
        }

        try {
            workerPool.execute(() -> {
                TenantContext.set(tenantId);
                try {
                    calibrationRepository.markRunning(runId, tenantId, Instant.now());
                    runCalculation(runId, tenantId, version);
                } catch (Exception e) {
                    log.error("Calibration worker failed for run {} tenant {}: {}", runId, tenantId, e.getMessage(), e);
                    calibrationRepository.markFailed(runId, tenantId, Instant.now(), e.getMessage());
                } finally {
                    TenantContext.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Background worker pool full for calibration run {}; marking failed", runId);
            calibrationRepository.markFailed(runId, tenantId, Instant.now(), "Worker pool busy");
        }

        return queuedReport;
    }

    /**
     * Synchronous evaluation: executes calibration and returns the persisted report.
     */
    public CalibrationReport evaluate(Long tenantId, String modelIdOrVersion) {
        String version = resolveTargetVersion(tenantId, modelIdOrVersion);
        UUID runId = UUID.randomUUID();

        if (!outcomeSource.isAvailable()) {
            int totalScored = findScoresForVersion(tenantId, version).size();
            CalibrationReport report = new CalibrationReport(
                    runId,
                    tenantId,
                    version,
                    "OUTCOMES_UNAVAILABLE",
                    outcomeSource.sourceName(),
                    false,
                    "Servicing feed is not configured (Design Decision 4). Realized loan outcomes are unavailable in this deployment.",
                    null,
                    new CalibrationSampleCounts(totalScored, 0, 0, 0, 0),
                    null,
                    Collections.emptyList(),
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    null
            );
            calibrationRepository.insert(report);
            return report;
        }

        CalibrationReport queuedReport = new CalibrationReport(
                runId, tenantId, version, "RUNNING", outcomeSource.sourceName(), true,
                "Evaluating calibration", null, new CalibrationSampleCounts(0, 0, 0, 0, 0),
                null, Collections.emptyList(), Instant.now(), Instant.now(), null, null
        );
        calibrationRepository.insert(queuedReport);
        runCalculation(runId, tenantId, version);
        return calibrationRepository.findById(runId).orElse(queuedReport);
    }

    private void runCalculation(UUID runId, Long tenantId, String version) {
        List<LoanScoreRecord> records = findScoresForVersion(tenantId, version);
        int totalScored = records.size();

        if (totalScored == 0) {
            calibrationRepository.markCompleted(
                    runId, tenantId, Instant.now(),
                    null,
                    new CalibrationSampleCounts(0, 0, 0, 0, 0),
                    null,
                    Collections.emptyList()
            );
            return;
        }

        List<String> loanIds = records.stream().map(LoanScoreRecord::getLoanId).toList();
        Map<String, LoanRealizedOutcome> outcomes = outcomeSource.getOutcomes(tenantId, loanIds);

        Instant minDate = null;
        Instant maxDate = null;
        List<EvaluatedItem> evaluated = new ArrayList<>();
        int missingOutcomes = 0;
        int defaultCount = 0;
        int prepayCount = 0;

        for (LoanScoreRecord record : records) {
            Instant computed = record.getComputedAt();
            if (computed != null) {
                if (minDate == null || computed.isBefore(minDate)) minDate = computed;
                if (maxDate == null || computed.isAfter(maxDate)) maxDate = computed;
            }

            LoanRealizedOutcome outcome = outcomes.get(record.getLoanId());
            if (outcome == null) {
                missingOutcomes++;
            } else {
                evaluated.add(new EvaluatedItem(record.getLoanId(), record.getCalibratedProbability(), outcome.defaulted(), outcome.prepaid()));
                if (outcome.defaulted()) defaultCount++;
                if (outcome.prepaid()) prepayCount++;
            }
        }

        EvaluationWindow window = (minDate != null || maxDate != null) ? new EvaluationWindow(minDate, maxDate) : null;
        CalibrationSampleCounts counts = new CalibrationSampleCounts(totalScored, evaluated.size(), missingOutcomes, defaultCount, prepayCount);

        if (evaluated.isEmpty()) {
            calibrationRepository.markCompleted(runId, tenantId, Instant.now(), window, counts, null, Collections.emptyList());
            return;
        }

        CalibrationMetrics metrics = computeMetrics(evaluated, defaultCount, prepayCount);
        List<CalibrationDecile> deciles = computeDeciles(evaluated);

        calibrationRepository.markCompleted(runId, tenantId, Instant.now(), window, counts, metrics, deciles);
    }

    private List<LoanScoreRecord> findScoresForVersion(Long tenantId, String targetVersion) {
        List<LoanScoreRecord> all = loanScoreRepository.findByIdTenantId(tenantId);
        if (targetVersion == null || targetVersion.isBlank() || "all".equalsIgnoreCase(targetVersion)) {
            return all;
        }
        return all.stream()
                .filter(r -> r.getModelVersion() != null &&
                        (r.getModelVersion().equals(targetVersion) || r.getModelVersion().startsWith(targetVersion + "@") || targetVersion.startsWith(r.getModelVersion())))
                .toList();
    }

    private String resolveTargetVersion(Long tenantId, String modelIdOrVersion) {
        if (modelIdOrVersion != null && !modelIdOrVersion.isBlank()) {
            return modelIdOrVersion;
        }
        List<String> versions = loanScoreRepository.findDistinctModelVersions(tenantId);
        if (!versions.isEmpty()) {
            return versions.get(0);
        }
        return DEFAULT_MODEL_PREFIX;
    }

    public static CalibrationMetrics computeMetrics(List<EvaluatedItem> evaluated, int defaults, int prepayments) {
        int n = evaluated.size();
        if (n == 0) return null;

        double sumPred = 0.0;
        double sumBrier = 0.0;
        for (EvaluatedItem item : evaluated) {
            sumPred += item.predicted;
            double actual = item.defaulted ? 1.0 : 0.0;
            double diff = item.predicted - actual;
            sumBrier += diff * diff;
        }

        double meanPredicted = sumPred / n;
        double obsDefaultRate = (double) defaults / n;
        double obsPrepayRate = (double) prepayments / n;
        Double calibrationRatio = obsDefaultRate > 0 ? (meanPredicted / obsDefaultRate) : null;
        double brierScore = sumBrier / n;

        Double auc = computeAuc(evaluated, defaults);
        Double ks = computeKs(evaluated, defaults);

        List<CalibrationDecile> deciles = computeDeciles(evaluated);
        Double hosmerLemeshow = computeHosmerLemeshow(deciles);

        return new CalibrationMetrics(
                meanPredicted,
                obsDefaultRate,
                obsPrepayRate,
                calibrationRatio,
                brierScore,
                auc,
                ks,
                hosmerLemeshow
        );
    }

    public static Double computeAuc(List<EvaluatedItem> evaluated, int defaults) {
        int n = evaluated.size();
        int nonDefaults = n - defaults;
        if (defaults == 0 || nonDefaults == 0) {
            return null; // undefined when only one class is present
        }

        List<EvaluatedItem> sorted = new ArrayList<>(evaluated);
        sorted.sort(Comparator.comparingDouble(a -> a.predicted));

        // Assign ranks with fractional ties
        double rankSumDefaults = 0.0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(sorted.get(j + 1).predicted, sorted.get(i).predicted) == 0) {
                j++;
            }
            double avgRank = ( (i + 1) + (j + 1) ) / 2.0;
            for (int k = i; k <= j; k++) {
                if (sorted.get(k).defaulted) {
                    rankSumDefaults += avgRank;
                }
            }
            i = j + 1;
        }

        double u = rankSumDefaults - ((double) defaults * (defaults + 1) / 2.0);
        double auc = u / ((double) defaults * nonDefaults);
        return Math.max(0.0, Math.min(1.0, auc));
    }

    public static Double computeKs(List<EvaluatedItem> evaluated, int defaults) {
        int n = evaluated.size();
        int nonDefaults = n - defaults;
        if (defaults == 0 || nonDefaults == 0) {
            return 0.0;
        }

        List<EvaluatedItem> sorted = new ArrayList<>(evaluated);
        sorted.sort(Comparator.comparingDouble(a -> a.predicted));

        double cumDefaults = 0.0;
        double cumNonDefaults = 0.0;
        double maxKs = 0.0;

        for (EvaluatedItem item : sorted) {
            if (item.defaulted) {
                cumDefaults += 1.0;
            } else {
                cumNonDefaults += 1.0;
            }
            double fDef = cumDefaults / defaults;
            double fNonDef = cumNonDefaults / nonDefaults;
            double sep = Math.abs(fDef - fNonDef);
            if (sep > maxKs) {
                maxKs = sep;
            }
        }
        return maxKs;
    }

    public static List<CalibrationDecile> computeDeciles(List<EvaluatedItem> evaluated) {
        int n = evaluated.size();
        if (n == 0) return Collections.emptyList();

        List<EvaluatedItem> sorted = new ArrayList<>(evaluated);
        sorted.sort(Comparator.comparingDouble(a -> a.predicted));

        int numBins = Math.min(10, n);
        List<CalibrationDecile> deciles = new ArrayList<>();

        for (int b = 0; b < numBins; b++) {
            int fromIdx = (int) Math.round((double) b * n / numBins);
            int toIdx = (int) Math.round((double) (b + 1) * n / numBins);
            if (fromIdx >= toIdx) continue;

            List<EvaluatedItem> binItems = sorted.subList(fromIdx, toIdx);
            int binN = binItems.size();
            double minP = binItems.get(0).predicted;
            double maxP = binItems.get(binN - 1).predicted;

            double sumP = 0.0;
            int binDef = 0;
            int binPrepay = 0;

            for (EvaluatedItem item : binItems) {
                sumP += item.predicted;
                if (item.defaulted) binDef++;
                if (item.prepaid) binPrepay++;
            }

            double meanP = sumP / binN;
            double obsRate = (double) binDef / binN;

            deciles.add(new CalibrationDecile(
                    b + 1,
                    minP,
                    maxP,
                    binN,
                    meanP,
                    obsRate,
                    binDef,
                    binPrepay
            ));
        }
        return Collections.unmodifiableList(deciles);
    }

    public static Double computeHosmerLemeshow(List<CalibrationDecile> deciles) {
        if (deciles == null || deciles.isEmpty()) return null;

        double chiSq = 0.0;
        for (CalibrationDecile dec : deciles) {
            int m = dec.sampleCount();
            double p = dec.meanPredicted();
            if (m == 0 || p <= 0.0 || p >= 1.0) continue;

            double expectedDef = m * p;
            double actualDef = dec.defaultCount();
            double diff = actualDef - expectedDef;
            double denom = m * p * (1.0 - p);
            if (denom > 1e-12) {
                chiSq += (diff * diff) / denom;
            }
        }
        return chiSq;
    }

    public record EvaluatedItem(String loanId, double predicted, boolean defaulted, boolean prepaid) {}
}
