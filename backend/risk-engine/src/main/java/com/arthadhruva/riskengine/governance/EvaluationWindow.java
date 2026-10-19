package com.arthadhruva.riskengine.governance;

import java.time.Instant;

public record EvaluationWindow(
        Instant start,
        Instant end
) {}
