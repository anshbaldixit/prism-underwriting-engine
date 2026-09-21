package dev.anshdixit.prism.scoring;

import java.util.List;
import java.util.Map;

/**
 * Immutable model definition, deserialised from {@code model/scorecard.json} (exported by ml/train_and_export.py).
 * A WoE scorecard is fully described by its bins, coefficients and intercept - there is no opaque state.
 */
public record Scorecard(
        String modelId,
        String version,
        String trainedAt,
        String algorithm,
        String target,
        double intercept,
        Scaling scaling,
        List<Feature> features,
        Map<String, String> reasonCodes,
        double minReasonContribution,
        DecisionPolicy decisionPolicy) {

    public record Scaling(int baseScore, int baseOdds, int pdo) {
    }

    public record Feature(
            String name,
            String label,
            String modality,
            String reasonCode,
            String missingReasonCode,
            double coefficient,
            double informationValue,
            double missingWoe,
            List<Bin> bins) {

        /** Bin lookup mirrors the Python binner: {@code lo <= x < hi}, open-ended when null. */
        public double woeFor(Double value) {
            if (value == null || value.isNaN()) {
                return missingWoe;
            }
            for (Bin b : bins) {
                boolean aboveLo = b.lo() == null || value >= b.lo();
                boolean belowHi = b.hi() == null || value < b.hi();
                if (aboveLo && belowHi) {
                    return b.woe();
                }
            }
            return missingWoe;
        }
    }

    public record Bin(Double lo, Double hi, double woe, int count) {
    }

    public record DecisionPolicy(
            double approveMaxPd,
            double referMaxPd,
            String notes,
            List<LimitTier> limitMultipliersOfMonthlyIncome,
            List<AprTier> aprTiers,
            String minEvidence) {
    }

    public record LimitTier(double maxPd, double multiplier) {
    }

    public record AprTier(int minScore, double apr) {
    }
}
