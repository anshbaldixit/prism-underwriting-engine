package dev.anshdixit.prism.fraud;

import java.util.List;

public record FraudResult(Outcome outcome, int points, List<FiredRule> firedRules, String rulesVersion) {

    public enum Outcome { PASS, STEP_UP, BLOCK }

    public record FiredRule(String id, String name, String signal, int points) {
    }

    /** Human-readable verification tasks derived from the signal families that fired. */
    public List<String> verificationItems() {
        return firedRules.stream()
                .map(FiredRule::signal)
                .distinct()
                .map(signal -> switch (signal) {
                    case "veracity" -> "Verify income with a recent pay stub or tax document";
                    case "synthetic-identity" -> "Perform document + liveness identity verification";
                    case "velocity" -> "Review other applications from this device";
                    case "behavioural" -> "Confirm application details with the applicant by phone";
                    default -> "Manual review";
                })
                .toList();
    }
}
