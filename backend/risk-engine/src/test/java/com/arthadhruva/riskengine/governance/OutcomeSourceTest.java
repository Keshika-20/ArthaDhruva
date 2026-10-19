package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.governance.outcome.FixtureOutcomeSource;
import com.arthadhruva.riskengine.governance.outcome.LoanRealizedOutcome;
import com.arthadhruva.riskengine.governance.outcome.OutcomeSource;
import com.arthadhruva.riskengine.governance.outcome.OutcomeType;
import com.arthadhruva.riskengine.governance.outcome.UnavailableOutcomeSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeSourceTest {

    @Test
    void unavailableSourceExplicitlyReportsUnavailable() {
        OutcomeSource unavailable = new UnavailableOutcomeSource();
        assertFalse(unavailable.isAvailable());
        assertEquals("unconfigured", unavailable.sourceName());
        assertTrue(unavailable.getOutcomes(1L, List.of("L1", "L2")).isEmpty());
    }

    @Test
    void fixtureSourceLoadsOutcomesDeterministically() {
        OutcomeSource fixture = new FixtureOutcomeSource("outcomes_fixture.json");
        assertTrue(fixture.isAvailable());
        assertEquals("fixture", fixture.sourceName());

        Map<String, LoanRealizedOutcome> outcomes = fixture.getOutcomes(1L, List.of("F21Q11429043", "F21Q40441280", "NON_EXISTENT_LOAN"));
        assertEquals(2, outcomes.size());

        LoanRealizedOutcome defaulted = outcomes.get("F21Q11429043");
        assertNotNull(defaulted);
        assertTrue(defaulted.defaulted());
        assertFalse(defaulted.prepaid());
        assertEquals(OutcomeType.DEFAULT, defaulted.type());

        LoanRealizedOutcome prepaid = outcomes.get("F21Q40441280");
        assertNotNull(prepaid);
        assertFalse(prepaid.defaulted());
        assertTrue(prepaid.prepaid());
        assertEquals(OutcomeType.PREPAYMENT, prepaid.type());

        assertNull(outcomes.get("NON_EXISTENT_LOAN"));
    }

    @Test
    void programmaticFixtureSource() {
        LoanRealizedOutcome o1 = LoanRealizedOutcome.defaultOutcome("L1", Instant.parse("2026-01-01T00:00:00Z"), Instant.now());
        LoanRealizedOutcome o2 = LoanRealizedOutcome.prepayOutcome("L2", Instant.parse("2026-02-01T00:00:00Z"), Instant.now());
        LoanRealizedOutcome o3 = LoanRealizedOutcome.performingOutcome("L3", Instant.now());

        OutcomeSource source = FixtureOutcomeSource.fromOutcomes(List.of(o1, o2, o3));
        assertTrue(source.isAvailable());

        Map<String, LoanRealizedOutcome> map = source.getOutcomes(1L, List.of("L1", "L2", "L3", "L4"));
        assertEquals(3, map.size());
        assertTrue(map.get("L1").defaulted());
        assertTrue(map.get("L2").prepaid());
        assertFalse(map.get("L3").defaulted());
        assertFalse(map.get("L3").prepaid());
        assertNull(map.get("L4"));
    }
}
