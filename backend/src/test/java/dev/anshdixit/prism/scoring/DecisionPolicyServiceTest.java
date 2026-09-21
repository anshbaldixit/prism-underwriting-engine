package dev.anshdixit.prism.scoring;

import dev.anshdixit.prism.fraud.FraudResult;
import dev.anshdixit.prism.model.ModelArtifactsConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyServiceTest {

    static Scorecard scorecard;
    static DecisionPolicyService policy;
    static ScorecardEngine engine;

    static final FraudResult PASS = new FraudResult(FraudResult.Outcome.PASS, 0, List.of(), "1.0.0");

    @BeforeAll
    static void setup() {
        scorecard = new ModelArtifactsConfig().scorecard();
        policy = new DecisionPolicyService(scorecard);
        engine = new ScorecardEngine(scorecard);
    }

    private static ScoreResult scoreWithPd(double pd) {
        double logit = Math.log(pd / (1 - pd));
        return new ScoreResult("m", "v", logit, pd, engine.toScore(logit), List.of(), List.of());
    }

    @Test
    void fraudBlockDeclinesBeforeAnyCreditLogic() {
        FraudResult block = new FraudResult(FraudResult.Outcome.BLOCK, 75, List.of(new FraudResult.FiredRule("F02", "pasted id", "synthetic-identity", 25)), "1.0.0");
        var out = policy.decide(scoreWithPd(0.01), block, ctx("thick", true, 5000, 60000, 4000.0));
        assertThat(out.decision()).isEqualTo(DecisionPolicyService.Decision.DECLINE);
        assertThat(out.basis()).isEqualTo(DecisionPolicyService.Basis.FRAUD_BLOCK);
        assertThat(out.creditLimit()).isNull();
        assertThat(out.verificationItems()).isNotEmpty();
    }

    @Test
    void stepUpRefersWithVerificationTasksEvenForPrimeApplicants() {
        FraudResult stepUp = new FraudResult(FraudResult.Outcome.STEP_UP, 40, List.of(new FraudResult.FiredRule("F01", "income inflated", "veracity", 40)), "1.0.0");
        var out = policy.decide(scoreWithPd(0.01), stepUp, ctx("thick", true, 5000, 60000, 4000.0));
        assertThat(out.decision()).isEqualTo(DecisionPolicyService.Decision.REFER);
        assertThat(out.basis()).isEqualTo(DecisionPolicyService.Basis.VERIFICATION_REQUIRED);
        assertThat(out.verificationItems()).anyMatch(s -> s.toLowerCase().contains("pay stub"));
    }

    @Test
    void newToCreditWithoutBankDataIsReferredNotDeclined() {
        var out = policy.decide(scoreWithPd(0.5), PASS, ctx("ntc", false, 2000, 30000, null));
        assertThat(out.decision()).isEqualTo(DecisionPolicyService.Decision.REFER);
        assertThat(out.basis()).isEqualTo(DecisionPolicyService.Basis.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void pdBandsMapToApproveReferDecline() {
        Scorecard.DecisionPolicy p = scorecard.decisionPolicy();
        assertThat(policy.decide(scoreWithPd(p.approveMaxPd() * 0.5), PASS, ctx("thick", true, 3000, 50000, 4000.0)).decision()).isEqualTo(DecisionPolicyService.Decision.APPROVE);
        assertThat(policy.decide(scoreWithPd((p.approveMaxPd() + p.referMaxPd()) / 2), PASS, ctx("thick", true, 3000, 50000, 4000.0)).decision()).isEqualTo(DecisionPolicyService.Decision.REFER);
        assertThat(policy.decide(scoreWithPd(Math.min(0.95, p.referMaxPd() * 1.5)), PASS, ctx("thick", true, 3000, 50000, 4000.0)).decision()).isEqualTo(DecisionPolicyService.Decision.DECLINE);
    }

    @Test
    void limitIsCappedByRequestAndByIncomeMultipleAndRoundedToFifty() {
        var out = policy.decide(scoreWithPd(0.02), PASS, ctx("thick", true, 9999, 40000, 3010.0));
        assertThat(out.decision()).isEqualTo(DecisionPolicyService.Decision.APPROVE);
        // PD 2% -> 3x monthly income = 9030 -> floor to 50 = 9000, below the 9999 requested
        assertThat(out.creditLimit()).isEqualTo(9000.0);
        var small = policy.decide(scoreWithPd(0.02), PASS, ctx("thick", true, 1200, 40000, 3010.0));
        assertThat(small.creditLimit()).isEqualTo(1200.0);
    }

    @Test
    void verifiedIncomeTakesPrecedenceOverStatedIncome() {
        var verified = policy.decide(scoreWithPd(0.02), PASS, ctx("thick", true, 20000, 120000, 2000.0));
        var stated = policy.decide(scoreWithPd(0.02), PASS, ctx("thick", true, 20000, 120000, null));
        assertThat(verified.creditLimit()).isEqualTo(6000.0);
        assertThat(stated.creditLimit()).isEqualTo(20000.0);
    }

    @Test
    void aprFollowsScoreTiers() {
        assertThat(policy.apr(700)).isEqualTo(14.99);
        assertThat(policy.apr(650)).isEqualTo(19.99);
        assertThat(policy.apr(500)).isEqualTo(24.99);
    }

    private static DecisionPolicyService.Context ctx(String fileType, boolean linked, double requested, double stated, Double verifiedMonthly) {
        return new DecisionPolicyService.Context(fileType, linked, requested, stated, verifiedMonthly);
    }

    @SuppressWarnings("unused")
    private static Map<String, Double> none() {
        return Map.of();
    }
}
