package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreOutcomeCalibrationJobTest {

    private ModelCalibrationService calibrationService;
    private OrganizationService organizations;
    private DistributedLock lock;
    private ScoreOutcomeCalibrationJob job;

    @BeforeEach
    void setUp() {
        calibrationService = Mockito.mock(ModelCalibrationService.class);
        organizations = Mockito.mock(OrganizationService.class);
        lock = Mockito.mock(DistributedLock.class);
        job = new ScoreOutcomeCalibrationJob(calibrationService, organizations, lock);
    }

    @Test
    void jobRunsOncePerPeriodAndEnqueuesForAllActiveTenants() {
        when(lock.period(any())).thenReturn("2026-10-19");
        when(organizations.activeIds()).thenReturn(List.of(1L, 2L));

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(lock).runOncePerPeriod(eq("score-outcome-calibration"), eq("2026-10-19"), any(Runnable.class));

        job.run();

        verify(calibrationService).enqueue(1L, null);
        verify(calibrationService).enqueue(2L, null);
        assertTrue(TenantContext.getOptional().isEmpty(), "TenantContext must be cleared after execution");
    }

    @Test
    void failureForOneTenantContinuesToNextTenant() {
        when(lock.period(any())).thenReturn("2026-10-19");
        when(organizations.activeIds()).thenReturn(List.of(1L, 2L));

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(lock).runOncePerPeriod(eq("score-outcome-calibration"), eq("2026-10-19"), any(Runnable.class));

        when(calibrationService.enqueue(1L, null)).thenThrow(new RuntimeException("Simulated error"));

        job.run();

        verify(calibrationService).enqueue(1L, null);
        verify(calibrationService).enqueue(2L, null);
        assertTrue(TenantContext.getOptional().isEmpty(), "TenantContext must be cleared even on failure");
    }
}
