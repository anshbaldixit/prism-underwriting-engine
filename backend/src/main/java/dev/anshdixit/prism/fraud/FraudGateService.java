package dev.anshdixit.prism.fraud;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Identity / veracity gate that runs BEFORE credit pricing. Behavioural and device signals live here and
 * only here: they can trigger verification or a block, but they never change the price or limit offered to
 * a legitimate applicant. Rules are additive points with two thresholds (STEP_UP, BLOCK).
 */
@Service
public class FraudGateService {

    private final FraudRules rules;

    public FraudGateService(FraudRules rules) {
        this.rules = rules;
    }

    public FraudResult evaluate(Map<String, Double> signals) {
        List<FraudResult.FiredRule> fired = rules.rules().stream()
                .filter(r -> r.fires(signals.get(r.feature())))
                .map(r -> new FraudResult.FiredRule(r.id(), r.name(), r.signal(), r.points()))
                .toList();
        int points = fired.stream().mapToInt(FraudResult.FiredRule::points).sum();
        FraudResult.Outcome outcome = points >= rules.thresholds().block() ? FraudResult.Outcome.BLOCK
                : points >= rules.thresholds().stepUp() ? FraudResult.Outcome.STEP_UP
                : FraudResult.Outcome.PASS;
        return new FraudResult(outcome, points, fired, rules.version());
    }
}
