package com.arthadhruva.riskengine.governance.outcome;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Default production implementation when no servicing feed is configured.
 *
 * <p>Explicitly reports outcomes as unavailable per Design Decision 4.
 * Never fabricates or synthesizes realized outcomes in production.
 */
public class UnavailableOutcomeSource implements OutcomeSource {

    public static final String SOURCE_NAME = "unconfigured";

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public Map<String, LoanRealizedOutcome> getOutcomes(Long tenantId, Collection<String> loanIds) {
        return Collections.emptyMap();
    }
}
