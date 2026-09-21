package dev.anshdixit.prism.scoring;

import java.util.List;

/**
 * Everything the engine knows about one scoring pass. Contributions are additive:
 * {@code logit = intercept + sum(contribution)} - which is what makes the reason codes exact.
 */
public record ScoreResult(
        String modelId,
        String modelVersion,
        double logit,
        double pd,
        int score,
        List<FeatureContribution> contributions,
        List<ReasonCode> reasonCodes) {

    public record FeatureContribution(
            String feature,
            String label,
            String modality,
            String reasonCode,
            Double value,
            boolean missing,
            double woe,
            double contribution) {
    }

    /** A Reg B "principal reason": the code, its consumer wording, and how many points it cost. */
    public record ReasonCode(String code, String text, String feature, double contribution) {
    }
}
