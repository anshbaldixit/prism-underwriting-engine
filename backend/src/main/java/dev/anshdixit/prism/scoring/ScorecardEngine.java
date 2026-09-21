package dev.anshdixit.prism.scoring;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-time scoring of a feature vector against the loaded scorecard.
 * Pure function of (scorecard, features): no I/O, trivially unit-testable, and identical to the Python
 * reference implementation (see {@code ScorecardEngineTest} which checks parity against exported rows).
 */
@Component
public class ScorecardEngine {

    /** Maximum number of principal reasons disclosed - Reg B practice is four. */
    public static final int MAX_REASON_CODES = 4;

    private final Scorecard scorecard;

    public ScorecardEngine(Scorecard scorecard) {
        this.scorecard = scorecard;
    }

    public Scorecard scorecard() {
        return scorecard;
    }

    /**
     * @param features feature name -> value; {@code null} (or absent) means "not observed", which maps to the
     *                 feature's dedicated missing bin rather than an imputed number.
     */
    public ScoreResult score(Map<String, Double> features) {
        List<ScoreResult.FeatureContribution> contributions = new ArrayList<>();
        double logit = scorecard.intercept();

        for (Scorecard.Feature f : scorecard.features()) {
            Double value = features.get(f.name());
            boolean missing = value == null || value.isNaN();
            double woe = f.woeFor(value);
            double contribution = f.coefficient() * woe;
            logit += contribution;
            contributions.add(new ScoreResult.FeatureContribution(
                    f.name(), f.label(), f.modality(), f.reasonCode(), missing ? null : value, missing, woe, contribution));
        }

        double pd = 1.0 / (1.0 + Math.exp(-logit));
        int score = toScore(logit);

        return new ScoreResult(scorecard.modelId(), scorecard.version(), logit, pd, score, contributions, principalReasons(contributions));
    }

    /**
     * Principal reasons = the reason codes that pushed the applicant furthest towards "bad". A feature that was
     * not observed (no bureau file, no linked account) is reported under its modality's missing-data code rather
     * than as e.g. "score below threshold" - absence of evidence is a different reason from bad evidence.
     * Contributions are aggregated per code, noise below the configured floor is dropped, and at most four are kept.
     * Mirrors {@code top_reasons} in ml/train_and_export.py (checked by ScorecardEngineTest parity).
     */
    List<ScoreResult.ReasonCode> principalReasons(List<ScoreResult.FeatureContribution> contributions) {
        Map<String, Double> byCode = new LinkedHashMap<>();
        Map<String, String> firstFeature = new LinkedHashMap<>();
        Map<String, String> missingCodes = new java.util.HashMap<>();
        for (Scorecard.Feature f : scorecard.features()) {
            missingCodes.put(f.name(), f.missingReasonCode());
        }
        for (ScoreResult.FeatureContribution c : contributions) {
            if (c.contribution() <= 0) {
                continue;
            }
            String code = c.missing() ? missingCodes.get(c.feature()) : c.reasonCode();
            byCode.merge(code, c.contribution(), Double::sum);
            firstFeature.putIfAbsent(code, c.feature());
        }
        return byCode.entrySet().stream()
                .filter(e -> e.getValue() > scorecard.minReasonContribution())
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(MAX_REASON_CODES)
                .map(e -> new ScoreResult.ReasonCode(e.getKey(), scorecard.reasonCodes().get(e.getKey()), firstFeature.get(e.getKey()), e.getValue()))
                .toList();
    }

    /** Standard scorecard scaling: {@code score = offset - factor * ln(odds_bad)}, PDO points to double the odds. */
    int toScore(double logit) {
        Scorecard.Scaling s = scorecard.scaling();
        double factor = s.pdo() / Math.log(2);
        double offset = s.baseScore() - factor * Math.log(s.baseOdds());
        return (int) Math.round(offset - factor * logit);
    }
}
