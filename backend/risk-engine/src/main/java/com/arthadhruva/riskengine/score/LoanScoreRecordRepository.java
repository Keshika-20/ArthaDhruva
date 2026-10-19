package com.arthadhruva.riskengine.score;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanScoreRecordRepository extends JpaRepository<LoanScoreRecord, LoanScoreId> {
    /** Every loan a tenant has scored so far, most recent first -- the portfolio view. Spring
     * Data reaches into the embedded id's own fields via the {@code Id<Field>} property-path
     * convention ({@code id.tenantId} -> {@code IdTenantId}). */
    Page<LoanScoreRecord> findAllByIdTenantIdOrderByComputedAtDesc(Long tenantId, Pageable pageable);

    /** Keyset page for exports (stable order, no OFFSET cost). */
    java.util.List<LoanScoreRecord> findByIdTenantIdAndIdLoanIdGreaterThanOrderByIdLoanIdAsc(Long tenantId, String afterLoanId, Pageable pageable);

    /** Most recent calibrated PDs for score-distribution monitoring (population stability). */
    @org.springframework.data.jpa.repository.Query("select r.calibratedProbability from LoanScoreRecord r where r.id.tenantId = :tenantId order by r.computedAt desc")
    java.util.List<Double> recentProbabilities(@org.springframework.data.repository.query.Param("tenantId") Long tenantId, Pageable pageable);

    /** All scores for a tenant. */
    java.util.List<LoanScoreRecord> findByIdTenantId(Long tenantId);

    /** Scores for a tenant and specific model version. */
    java.util.List<LoanScoreRecord> findByIdTenantIdAndModelVersion(Long tenantId, String modelVersion);

    /** Distinct model versions scored for a tenant. */
    @org.springframework.data.jpa.repository.Query("select distinct r.modelVersion from LoanScoreRecord r where r.id.tenantId = :tenantId and r.modelVersion is not null")
    java.util.List<String> findDistinctModelVersions(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);
}
