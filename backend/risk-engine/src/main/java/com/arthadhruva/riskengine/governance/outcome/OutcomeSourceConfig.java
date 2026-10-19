package com.arthadhruva.riskengine.governance.outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing the active {@link OutcomeSource}.
 *
 * <p>In production, defaults to {@link UnavailableOutcomeSource} (per Design Decision 4).
 * For automated test suites or local staging with fixture data, {@code risk.outcomes.source=fixture}
 * can be configured to supply deterministic loan outcomes.
 */
@Configuration
public class OutcomeSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(OutcomeSourceConfig.class);

    @Bean
    @ConditionalOnMissingBean(OutcomeSource.class)
    public OutcomeSource outcomeSource(@Value("${risk.outcomes.source:unconfigured}") String source,
                                       @Value("${risk.outcomes.fixture-path:outcomes_fixture.json}") String fixturePath) {
        if ("fixture".equalsIgnoreCase(source.trim())) {
            log.info("Configuring deterministic fixture OutcomeSource from '{}'", fixturePath);
            return new FixtureOutcomeSource(fixturePath);
        }
        log.info("No servicing feed configured; realized loan outcomes are explicitly UNAVAILABLE (Design Decision 4)");
        return new UnavailableOutcomeSource();
    }
}
