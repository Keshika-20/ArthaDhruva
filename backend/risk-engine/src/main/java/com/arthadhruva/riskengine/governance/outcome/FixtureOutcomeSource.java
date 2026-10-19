package com.arthadhruva.riskengine.governance.outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic fixture-backed outcome source for automated tests and validation.
 */
public class FixtureOutcomeSource implements OutcomeSource {

    private static final Logger log = LoggerFactory.getLogger(FixtureOutcomeSource.class);
    public static final String SOURCE_NAME = "fixture";

    private final Map<String, LoanRealizedOutcome> fixtureOutcomes;
    private final boolean available;

    public FixtureOutcomeSource(Map<String, LoanRealizedOutcome> outcomes) {
        this.fixtureOutcomes = new HashMap<>(outcomes);
        this.available = true;
    }

    public FixtureOutcomeSource(String resourcePath) {
        this.fixtureOutcomes = loadFromResource(resourcePath);
        this.available = true;
    }

    public FixtureOutcomeSource() {
        this("outcomes_fixture.json");
    }

    public static FixtureOutcomeSource fromOutcomes(Collection<LoanRealizedOutcome> outcomes) {
        Map<String, LoanRealizedOutcome> map = new HashMap<>();
        for (LoanRealizedOutcome outcome : outcomes) {
            map.put(outcome.loanId(), outcome);
        }
        return new FixtureOutcomeSource(map);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public Map<String, LoanRealizedOutcome> getOutcomes(Long tenantId, Collection<String> loanIds) {
        if (!available || loanIds == null || loanIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, LoanRealizedOutcome> result = new HashMap<>();
        for (String loanId : loanIds) {
            LoanRealizedOutcome outcome = fixtureOutcomes.get(loanId);
            if (outcome != null) {
                result.put(loanId, outcome);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, LoanRealizedOutcome> loadFromResource(String path) {
        Map<String, LoanRealizedOutcome> map = new HashMap<>();
        try (InputStream is = FixtureOutcomeSource.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Fixture resource '{}' not found on classpath; starting with empty fixture", path);
                return map;
            }
            ObjectMapper mapper = new ObjectMapper();
            List<FixtureEntry> entries = mapper.readValue(is.readAllBytes(), new TypeReference<List<FixtureEntry>>() {});
            for (FixtureEntry entry : entries) {
                OutcomeType type = entry.type != null ? OutcomeType.valueOf(entry.type) :
                        (entry.defaulted ? OutcomeType.DEFAULT : (entry.prepaid ? OutcomeType.PREPAYMENT : OutcomeType.ACTIVE_PERFORMING));
                Instant observedAt = entry.observedAt != null ? Instant.parse(entry.observedAt) : Instant.now();
                Instant eventDate = entry.eventDate != null ? Instant.parse(entry.eventDate) : null;
                map.put(entry.loanId, new LoanRealizedOutcome(entry.loanId, type, entry.defaulted, entry.prepaid, observedAt, eventDate));
            }
        } catch (Exception e) {
            log.error("Could not parse fixture outcomes from resource '{}': {}", path, e.getMessage(), e);
        }
        return map;
    }

    public static class FixtureEntry {
        public String loanId;
        public String type;
        public boolean defaulted;
        public boolean prepaid;
        public String observedAt;
        public String eventDate;
    }
}
