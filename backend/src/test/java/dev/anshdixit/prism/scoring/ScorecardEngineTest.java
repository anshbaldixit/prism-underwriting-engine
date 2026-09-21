package dev.anshdixit.prism.scoring;

import dev.anshdixit.prism.model.ModelArtifactsConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScorecardEngineTest {

    static Scorecard scorecard;
    static ScorecardEngine engine;

    @BeforeAll
    static void load() {
        scorecard = new ModelArtifactsConfig().scorecard();
        engine = new ScorecardEngine(scorecard);
    }

    @Test
    void contributionsAreAdditiveAndSumToLogit() {
        ScoreResult r = engine.score(Map.of("bureau_score", 700.0, "monthly_income", 4000.0, "income_cv", 0.2));
        double sum = scorecard.intercept() + r.contributions().stream().mapToDouble(ScoreResult.FeatureContribution::contribution).sum();
        assertThat(r.logit()).isCloseTo(sum, within(1e-9));
        assertThat(r.pd()).isCloseTo(1 / (1 + Math.exp(-r.logit())), within(1e-12));
        assertThat(r.contributions()).hasSize(scorecard.features().size());
    }

    @Test
    void missingFeaturesUseTheDedicatedMissingBinNotZero() {
        ScoreResult withNothing = engine.score(Map.of());
        assertThat(withNothing.contributions()).allMatch(ScoreResult.FeatureContribution::missing);
        Scorecard.Feature bureau = scorecard.features().stream().filter(f -> f.name().equals("bureau_score")).findFirst().orElseThrow();
        ScoreResult.FeatureContribution c = withNothing.contributions().stream().filter(x -> x.feature().equals("bureau_score")).findFirst().orElseThrow();
        assertThat(c.woe()).isEqualTo(bureau.missingWoe());
    }

    @Test
    void higherBureauScoreNeverIncreasesRisk() {
        Map<String, Double> lo = new HashMap<>(Map.of("bureau_score", 560.0));
        Map<String, Double> hi = new HashMap<>(Map.of("bureau_score", 780.0));
        assertThat(engine.score(hi).pd()).isLessThan(engine.score(lo).pd());
    }

    @Test
    void reasonCodesAreTheLargestAdverseContributionsAtMostFour() {
        ScoreResult r = engine.score(Map.of("bureau_score", 560.0, "delinquencies_24m", 3.0, "nsf_count_6m", 4.0, "income_cv", 0.9,
                "obligation_ratio", 0.8, "gambling_flag", 1.0, "rent_ontime_ratio", 0.3));
        assertThat(r.reasonCodes()).hasSizeLessThanOrEqualTo(ScorecardEngine.MAX_REASON_CODES);
        List<Double> contributions = r.reasonCodes().stream().map(ScoreResult.ReasonCode::contribution).toList();
        assertThat(contributions).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(contributions).allMatch(c -> c > 0);
        assertThat(r.reasonCodes()).allMatch(rc -> rc.text() != null && !rc.text().isBlank());
    }

    @Test
    void scoreScalingMatchesTwentyPointsToDoubleTheOdds() {
        int base = engine.toScore(Math.log(1.0 / scorecard.scaling().baseOdds()));
        assertThat(base).isEqualTo(scorecard.scaling().baseScore());
        int doubled = engine.toScore(Math.log(1.0 / (2.0 * scorecard.scaling().baseOdds())));
        assertThat(doubled - base).isEqualTo(scorecard.scaling().pdo());
    }

    /** Parity with the Python reference implementation on rows it exported (score, PD and reason codes). */
    @Test
    void reproducesPythonReferenceScoresOnExportedHoldoutRows() throws Exception {
        JsonNode rows;
        try (InputStream in = new ClassPathResource("model/historical_sample.json").getInputStream()) {
            rows = ModelArtifactsConfig.modelJson().readTree(in);
        }
        int checked = 0;
        for (JsonNode row : rows) {
            if (checked++ >= 300) {
                break;
            }
            Map<String, Double> f = new HashMap<>();
            row.get("features").properties().forEach(e -> f.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asDouble()));
            ScoreResult r = engine.score(f);
            assertThat(r.pd()).as("pd for %s", row.get("applicant_id")).isCloseTo(row.get("pd").asDouble(), within(1e-3));
            assertThat(r.score()).as("score for %s", row.get("applicant_id")).isCloseTo(row.get("score").asInt(), within(1));
            List<String> expectedCodes = row.get("reason_codes").valueStream().map(JsonNode::asString).toList();
            assertThat(r.reasonCodes().stream().map(ScoreResult.ReasonCode::code).toList()).isEqualTo(expectedCodes);
        }
        assertThat(checked).isGreaterThan(100);
    }
}
