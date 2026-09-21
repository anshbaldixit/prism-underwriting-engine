package dev.anshdixit.prism.fraud;

import java.util.List;

public record FraudResult(Outcome outcome, int points, List<FiredRule> firedRules, String rulesVersion) {

    public enum Outcome { PASS, STEP_UP, BLOCK }

    public record FiredRule(String id, String name, String signal, int points) {
    }

    /** What the applicant is told - never internal detail such as device velocity. */
    public List<String> applicantFacingItems() {
        return firedRules.stream()
                .map(FiredRule::signal)
                .distinct()
                .map(signal -> switch (signal) {
                    case "veracity" -> "Provide a recent pay stub or tax document to confirm your income";
                    case "synthetic-identity" -> "Complete a short identity verification (photo ID and selfie)";
                    default -> "A brief additional review of your application";
                })
                .distinct()
                .toList();
    }

    /** Internal verification tasks for the underwriter, derived from the signal families that fired. */
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
