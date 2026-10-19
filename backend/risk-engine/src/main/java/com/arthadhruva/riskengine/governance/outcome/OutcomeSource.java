package com.arthadhruva.riskengine.governance.outcome;

import java.util.Collection;
import java.util.Map;

/**
 * Abstraction for feeds providing realized loan outcomes (defaults and prepayments).
 *
 * <p>Per Design Decision 4, production deployments have no servicing feed by default and must
 * explicitly report outcomes as unavailable rather than fabricating or silently approximating them.
 * Automated tests use deterministic fixture-backed sources.
 */
public interface OutcomeSource {

    /**
     * Whether realized loan outcomes can be obtained from this source in the current deployment.
     */
    boolean isAvailable();

    /**
     * Identifier or description of this source (e.g. "unconfigured", "fixture", "servicing_feed").
     */
    String sourceName();

    /**
     * Obtains realized outcomes for the requested loan IDs for the specified tenant.
     * Loans not found in the returned map represent missing / unobserved outcomes.
     */
    Map<String, LoanRealizedOutcome> getOutcomes(Long tenantId, Collection<String> loanIds);
}
