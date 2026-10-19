package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job comparing stored scores with realized outcomes (Issue #27).
 *
 * <p>Cluster-wide coordination: runs once per period across all replicas using {@link DistributedLock}.
 * Enqueues or records work quickly, keeping the scheduler thread free in milliseconds while heavy
 * evaluation executes asynchronously on the background worker pool.
 */
@Component
public class ScoreOutcomeCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(ScoreOutcomeCalibrationJob.class);

    private final ModelCalibrationService calibrationService;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    public ScoreOutcomeCalibrationJob(ModelCalibrationService calibrationService,
                                      OrganizationService organizations,
                                      DistributedLock lock) {
        this.calibrationService = calibrationService;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${risk.calibration.cron:0 15 3 * * *}")
    public void run() {
        lock.runOncePerPeriod("score-outcome-calibration", lock.period(DistributedLock.DAILY), () -> {
            int count = 0;
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    calibrationService.enqueue(tenantId, null);
                    count++;
                } catch (Exception e) {
                    log.error("Could not enqueue score outcome calibration for tenant {}; continuing", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
            log.info("Score outcome calibration pass completed; recorded/enqueued for {} tenant(s)", count);
        });
    }
}
