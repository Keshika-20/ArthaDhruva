package com.arthadhruva.riskengine.governance.outcome;

import java.time.Instant;

/**
 * An observed realized outcome for a single loan.
 *
 * @param loanId the loan identifier
 * @param type the type of outcome (default, prepay, performing, matured)
 * @param defaulted whether a default event occurred within the observation window
 * @param prepaid whether a prepayment occurred before default
 * @param observedAt when this outcome was recorded/observed
 * @param eventDate optional date when the default or prepayment occurred
 */
public record LoanRealizedOutcome(
        String loanId,
        OutcomeType type,
        boolean defaulted,
        boolean prepaid,
        Instant observedAt,
        Instant eventDate
) {
    public static LoanRealizedOutcome defaultOutcome(String loanId, Instant eventDate, Instant observedAt) {
        return new LoanRealizedOutcome(loanId, OutcomeType.DEFAULT, true, false, observedAt, eventDate);
    }

    public static LoanRealizedOutcome prepayOutcome(String loanId, Instant eventDate, Instant observedAt) {
        return new LoanRealizedOutcome(loanId, OutcomeType.PREPAYMENT, false, true, observedAt, eventDate);
    }

    public static LoanRealizedOutcome performingOutcome(String loanId, Instant observedAt) {
        return new LoanRealizedOutcome(loanId, OutcomeType.ACTIVE_PERFORMING, false, false, observedAt, null);
    }

    public static LoanRealizedOutcome maturedOutcome(String loanId, Instant observedAt) {
        return new LoanRealizedOutcome(loanId, OutcomeType.MATURED_NO_DEFAULT, false, false, observedAt, null);
    }
}
