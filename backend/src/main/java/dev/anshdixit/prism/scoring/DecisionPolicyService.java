package dev.anshdixit.prism.scoring;

import dev.anshdixit.prism.fraud.FraudResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a score and a fraud-gate result into an auditable decision. Order of precedence is deliberate:
 * <ol>
 *   <li>Fraud BLOCK  - never reaches pricing.</li>
 *   <li>Fraud STEP_UP - referred with verification tasks; credit view still computed for the underwriter.</li>
 *   <li>Insufficient evidence (no file AND no linked bank data) - referred, not declined: the applicant is
 *       invited to link an account, which is the inclusion path this engine exists for.</li>
 *   <li>PD bands from the scorecard's calibrated policy.</li>
 * </ol>
 */
@Service
public class DecisionPolicyService {

    private final Scorecard scorecard;

    public DecisionPolicyService(Scorecard scorecard) {
        this.scorecard = scorecard;
    }

    public DecisionOutcome decide(ScoreResult score, FraudResult fraud, Context ctx) {
        Scorecard.DecisionPolicy policy = scorecard.decisionPolicy();
        List<String> notes = new ArrayList<>();

        if (fraud.outcome() == FraudResult.Outcome.BLOCK) {
            notes.add("Identity/verification checks failed (" + fraud.points() + " points). Routed to fraud review.");
            return new DecisionOutcome(Decision.DECLINE, Basis.FRAUD_BLOCK, null, null, fraud.verificationItems(), notes);
        }
        if (fraud.outcome() == FraudResult.Outcome.STEP_UP) {
            notes.add("Verification required before a credit decision (" + fraud.points() + " points).");
            return new DecisionOutcome(Decision.REFER, Basis.VERIFICATION_REQUIRED, null, null, fraud.verificationItems(), notes);
        }
        if ("ntc".equals(ctx.fileType()) && !ctx.bankLinked()) {
            notes.add(policy.minEvidence());
            return new DecisionOutcome(Decision.REFER, Basis.INSUFFICIENT_EVIDENCE, null, null,
                    List.of("Invite applicant to link a bank account so cash-flow data can be assessed"), notes);
        }

        double pd = score.pd();
        if (pd <= policy.approveMaxPd()) {
            double limit = creditLimit(pd, ctx);
            double apr = apr(score.score());
            notes.add(String.format("PD %.1f%% within approve band (<= %.1f%%).", pd * 100, policy.approveMaxPd() * 100));
            return new DecisionOutcome(Decision.APPROVE, Basis.CREDIT_POLICY, limit, apr, List.of(), notes);
        }
        if (pd <= policy.referMaxPd()) {
            notes.add(String.format("PD %.1f%% in refer band (%.1f%% - %.1f%%).", pd * 100, policy.approveMaxPd() * 100, policy.referMaxPd() * 100));
            return new DecisionOutcome(Decision.REFER, Basis.CREDIT_POLICY, null, null, List.of("Underwriter review of principal risk factors"), notes);
        }
        notes.add(String.format("PD %.1f%% above refer band (> %.1f%%).", pd * 100, policy.referMaxPd() * 100));
        return new DecisionOutcome(Decision.DECLINE, Basis.CREDIT_POLICY, null, null, List.of(), notes);
    }

    /** Limit = min(requested, multiplier(PD) x monthly income), rounded down to 50. Verified income wins over stated. */
    double creditLimit(double pd, Context ctx) {
        double monthlyIncome = ctx.verifiedMonthlyIncome() != null ? ctx.verifiedMonthlyIncome() : ctx.statedAnnualIncome() / 12.0;
        double multiplier = scorecard.decisionPolicy().limitMultipliersOfMonthlyIncome().stream()
                .filter(t -> pd <= t.maxPd())
                .findFirst()
                .map(Scorecard.LimitTier::multiplier)
                .orElse(1.0);
        double raw = Math.min(ctx.requestedAmount(), multiplier * monthlyIncome);
        return Math.max(0, Math.floor(raw / 50.0) * 50.0);
    }

    double apr(int score) {
        return scorecard.decisionPolicy().aprTiers().stream()
                .filter(t -> score >= t.minScore())
                .findFirst()
                .map(Scorecard.AprTier::apr)
                .orElseThrow();
    }

    public enum Decision { APPROVE, REFER, DECLINE }

    public enum Basis { CREDIT_POLICY, FRAUD_BLOCK, VERIFICATION_REQUIRED, INSUFFICIENT_EVIDENCE }

    public record Context(String fileType, boolean bankLinked, double requestedAmount, double statedAnnualIncome, Double verifiedMonthlyIncome) {
    }

    public record DecisionOutcome(Decision decision, Basis basis, Double creditLimit, Double apr, List<String> verificationItems, List<String> policyNotes) {
    }
}
