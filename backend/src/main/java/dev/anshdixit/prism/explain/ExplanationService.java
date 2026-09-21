package dev.anshdixit.prism.explain;

import dev.anshdixit.prism.ai.GuardrailService;
import dev.anshdixit.prism.ai.LlmRequest;
import dev.anshdixit.prism.ai.LlmTask;
import dev.anshdixit.prism.ai.PromptTemplateService;
import dev.anshdixit.prism.cashflow.CashflowFeatures;
import dev.anshdixit.prism.config.PrismProperties;
import dev.anshdixit.prism.fraud.FraudResult;
import dev.anshdixit.prism.scoring.DecisionPolicyService;
import dev.anshdixit.prism.scoring.ScoreResult;
import dev.anshdixit.prism.similar.SimilarApplicantService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the structured context and prompts for the two decision-time explanations and runs them through
 * the guardrail pipeline. The context contains derived numbers and reason codes only - no name, no contact
 * details, no identifiers - so a prompt can be logged, replayed and reviewed without handling PII.
 */
@Service
public class ExplanationService {

    private final PromptTemplateService prompts;
    private final GuardrailService guardrails;
    private final int maxTokens;

    public ExplanationService(PromptTemplateService prompts, GuardrailService guardrails, PrismProperties props) {
        this.prompts = prompts;
        this.guardrails = guardrails;
        this.maxTokens = props.ai().maxOutputTokens();
    }

    public record Inputs(UUID applicationId, String fileType, boolean bankLinked, double requestedAmount, double statedAnnualIncome,
                         ScoreResult score, FraudResult fraud, DecisionPolicyService.DecisionOutcome outcome,
                         CashflowFeatures cashflow, SimilarApplicantService.Cohort cohort) {
    }

    public GuardrailService.Guarded adverseActionNotice(Inputs in) {
        Map<String, Object> ctx = baseContext(in);
        Map<String, Object> vars = new HashMap<>();
        vars.put("decision", in.outcome().decision().name());
        vars.put("basis", in.outcome().basis().name());
        vars.put("requestedAmount", String.format("%,.0f", in.requestedAmount()));
        vars.put("offerLine", in.outcome().creditLimit() == null ? "" :
                String.format("Offer: credit line $%,.0f at %.2f%% APR", in.outcome().creditLimit(), in.outcome().apr()));
        vars.put("verificationItems", in.outcome().verificationItems().isEmpty() ? "none" : String.join("; ", in.outcome().verificationItems()));
        vars.put("reasonsBlock", reasonsBlock(in.score()));
        return guardrails.generate(new LlmRequest(LlmTask.ADVERSE_ACTION_NOTICE, prompts.system(LlmTask.ADVERSE_ACTION_NOTICE),
                prompts.user(LlmTask.ADVERSE_ACTION_NOTICE, vars), ctx, maxTokens), in.applicationId());
    }

    public GuardrailService.Guarded underwriterSummary(Inputs in) {
        Map<String, Object> ctx = baseContext(in);
        Map<String, Object> vars = new HashMap<>();
        vars.put("fileType", in.fileType());
        vars.put("bankLinked", in.bankLinked() ? "yes" : "no");
        vars.put("decision", in.outcome().decision().name());
        vars.put("basis", in.outcome().basis().name());
        vars.put("score", in.score().score());
        vars.put("pdPct", String.format("%.1f", in.score().pd() * 100));
        vars.put("requestedAmount", String.format("%,.0f", in.requestedAmount()));
        vars.put("statedAnnualIncome", String.format("%,.0f", in.statedAnnualIncome()));
        vars.put("verifiedMonthlyIncome", in.cashflow() == null ? "not available" : String.format("$%,.0f", in.cashflow().monthlyIncome()));
        vars.put("reasonsBlock", reasonsBlock(in.score()));
        vars.put("strengthsBlock", strengthsBlock(in.score()));
        vars.put("fraudOutcome", in.fraud().outcome().name());
        vars.put("fraudPoints", in.fraud().points());
        vars.put("fraudRulesLine", in.fraud().firedRules().isEmpty() ? "" : ": " + String.join("; ", in.fraud().firedRules().stream().map(FraudResult.FiredRule::name).toList()));
        vars.put("verificationItems", in.outcome().verificationItems().isEmpty() ? "none" : String.join("; ", in.outcome().verificationItems()));
        vars.put("cashflowSummary", cashflowSummary(in.cashflow()));
        vars.put("similarCohort", cohortSummary(in.cohort()));
        return guardrails.generate(new LlmRequest(LlmTask.UNDERWRITER_SUMMARY, prompts.system(LlmTask.UNDERWRITER_SUMMARY),
                prompts.user(LlmTask.UNDERWRITER_SUMMARY, vars), ctx, maxTokens), in.applicationId());
    }

    /** The facts the validator will hold the model to. */
    private Map<String, Object> baseContext(Inputs in) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("decision", in.outcome().decision().name());
        ctx.put("basis", in.outcome().basis().name());
        ctx.put("fileType", in.fileType());
        ctx.put("score", in.score().score());
        ctx.put("pd", in.score().pd());
        ctx.put("creditLimit", in.outcome().creditLimit());
        ctx.put("apr", in.outcome().apr());
        ctx.put("verificationItems", in.outcome().verificationItems());
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (ScoreResult.ReasonCode r : in.score().reasonCodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("text", r.text());
            m.put("feature", r.feature());
            m.put("value", valueOf(in.score(), r.feature()));
            reasons.add(m);
        }
        ctx.put("reasons", reasons);
        ctx.put("strengths", strengths(in.score()));
        Map<String, Object> fraud = new LinkedHashMap<>();
        fraud.put("outcome", in.fraud().outcome().name());
        fraud.put("points", in.fraud().points());
        fraud.put("firedRules", in.fraud().firedRules().stream().map(FraudResult.FiredRule::name).toList());
        ctx.put("fraud", fraud);
        return ctx;
    }

    static List<Map<String, Object>> strengths(ScoreResult score) {
        return score.contributions().stream()
                .filter(c -> c.contribution() < 0 && !c.missing())
                .sorted(Comparator.comparingDouble(ScoreResult.FeatureContribution::contribution))
                .limit(4)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", c.reasonCode());
                    m.put("label", c.label());
                    m.put("contribution", c.contribution());
                    return m;
                })
                .toList();
    }

    private static Double valueOf(ScoreResult score, String feature) {
        return score.contributions().stream().filter(c -> c.feature().equals(feature)).map(ScoreResult.FeatureContribution::value).findFirst().orElse(null);
    }

    private static String reasonsBlock(ScoreResult score) {
        if (score.reasonCodes().isEmpty()) {
            return "(none - no risk-increasing factors)";
        }
        StringBuilder sb = new StringBuilder();
        for (ScoreResult.ReasonCode r : score.reasonCodes()) {
            Double v = valueOf(score, r.feature());
            sb.append("- ").append(r.code()).append(": ").append(r.text())
                    .append(v == null ? " (not observed)" : String.format(" (observed value %.2f)", v)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String strengthsBlock(ScoreResult score) {
        List<Map<String, Object>> s = strengths(score);
        if (s.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : s) {
            sb.append("- ").append(m.get("code")).append(": ").append(m.get("label")).append('\n');
        }
        return sb.toString().trim();
    }

    static String cashflowSummary(CashflowFeatures cf) {
        if (cf == null) {
            return "no linked account";
        }
        return String.format("verified income $%,.0f/month over %d months, income volatility %.2f, balance trend %+.2f, min balance %.2fx income, " +
                        "%d overdraft events, rent regularity %s, utilities %s, phone %s, obligations %.0f%% of income, discretionary %.0f%% of spend%s",
                cf.monthlyIncome(), cf.monthsObserved(), cf.incomeCv(), cf.balanceTrend(), cf.minBalanceRatio(), cf.nsfCount(),
                pct(cf.rentOntimeRatio()), pct(cf.utilityOntimeRatio()), pct(cf.telcoOntimeRatio()), cf.obligationRatio() * 100,
                cf.discretionaryRatio() * 100, cf.gamblingFlag() ? ", gambling activity present" : "");
    }

    static String cohortSummary(SimilarApplicantService.Cohort cohort) {
        if (cohort == null || cohort.size() == 0) {
            return "not available";
        }
        return String.format("%d nearest historical applicants: %.0f%% were approved, %.1f%% defaulted within 12 months",
                cohort.size(), cohort.approvalRate() * 100, cohort.defaultRate() * 100);
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : String.format("%.0f%%", v * 100);
    }
}
