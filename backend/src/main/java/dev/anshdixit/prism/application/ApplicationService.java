package dev.anshdixit.prism.application;

import dev.anshdixit.prism.ai.GuardrailService;
import dev.anshdixit.prism.cashflow.CashflowFeatureService;
import dev.anshdixit.prism.cashflow.CashflowFeatures;
import dev.anshdixit.prism.common.IdHasher;
import dev.anshdixit.prism.common.NotFoundException;
import dev.anshdixit.prism.explain.ExplanationService;
import dev.anshdixit.prism.fraud.FraudGateService;
import dev.anshdixit.prism.fraud.FraudResult;
import dev.anshdixit.prism.scoring.Decision;
import dev.anshdixit.prism.scoring.DecisionPolicyService;
import dev.anshdixit.prism.scoring.DecisionRepository;
import dev.anshdixit.prism.scoring.ScoreResult;
import dev.anshdixit.prism.scoring.ScorecardEngine;
import dev.anshdixit.prism.similar.ProfileTextBuilder;
import dev.anshdixit.prism.similar.SimilarApplicantService;
import dev.anshdixit.prism.underwriting.UnderwriterAction;
import dev.anshdixit.prism.underwriting.UnderwriterActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The real-time underwriting pipeline, in order:
 * persist -> categorise transactions (pgvector) -> derive cash-flow features -> fraud gate -> scorecard ->
 * decision policy -> profile embedding + similar cohort -> guardrailed notice -> decision record.
 * Everything up to the notice is deterministic and typically completes in tens of milliseconds; the LLM
 * step is the only network-bound stage and degrades to a template if it fails.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applications;
    private final BankTransactionRepository transactions;
    private final DecisionRepository decisions;
    private final UnderwriterActionRepository actions;
    private final CashflowFeatureService cashflow;
    private final FraudGateService fraudGate;
    private final ScorecardEngine engine;
    private final DecisionPolicyService policy;
    private final ProfileTextBuilder profiles;
    private final SimilarApplicantService similar;
    private final ExplanationService explanations;
    private final IdHasher idHasher;
    private final JsonMapper json;

    public ApplicationService(ApplicationRepository applications, BankTransactionRepository transactions, DecisionRepository decisions,
                              UnderwriterActionRepository actions, CashflowFeatureService cashflow, FraudGateService fraudGate,
                              ScorecardEngine engine, DecisionPolicyService policy, ProfileTextBuilder profiles,
                              SimilarApplicantService similar, ExplanationService explanations, IdHasher idHasher, JsonMapper json) {
        this.applications = applications;
        this.transactions = transactions;
        this.decisions = decisions;
        this.actions = actions;
        this.cashflow = cashflow;
        this.fraudGate = fraudGate;
        this.engine = engine;
        this.policy = policy;
        this.profiles = profiles;
        this.similar = similar;
        this.explanations = explanations;
        this.idHasher = idHasher;
        this.json = json;
    }

    @Transactional
    public ApplicationDetail submit(SubmitApplicationRequest req, String username) {
        long t0 = System.nanoTime();
        boolean hasTransactions = req.transactions() != null && !req.transactions().isEmpty();
        if (hasTransactions && !req.consent().bankData()) {
            throw new IllegalArgumentException("Bank transaction data cannot be processed without explicit consent");
        }
        boolean bankLinked = hasTransactions && req.consent().bankData();

        Application app = new Application(username, req.personaId(), req.applicant().fullName(), req.applicant().email(), req.applicant().phone(),
                idHasher.hash(req.applicant().nationalId()), req.applicant().employmentType(), req.applicant().statedAnnualIncome(),
                req.loan().requestedAmount(), req.loan().purpose(), req.consent().bankData(), req.consent().altData(), bankLinked,
                req.bureau().fileType());
        if (!"ntc".equals(req.bureau().fileType())) {
            app.setBureau(dec(req.bureau().bureauScore()), dec(req.bureau().monthsOnFile()), dec(req.bureau().tradelines()),
                    dec(req.bureau().inquiries6m()), dec(req.bureau().delinquencies24m()));
        }

        // Device velocity is computed server-side from what this system has actually seen (plus a persona seed for demos).
        SubmitApplicationRequest.Behaviour b = req.behaviour() == null ? new SubmitApplicationRequest.Behaviour(null, null, null, null, null, null, null, null) : req.behaviour();
        int deviceApps = b.deviceAppsLast30dSeed() == null ? 0 : b.deviceAppsLast30dSeed();
        if (b.deviceId() != null && !b.deviceId().isBlank()) {
            deviceApps += (int) applications.countByDeviceSince(b.deviceId(), Instant.now().minus(Duration.ofDays(30)));
        }
        app.setBehaviour(dec(b.sessionSeconds()), b.incomeFieldEdits(), b.pasteSsn(), b.pasteIncome(), dec(b.emailAgeDays()), b.voipPhone(), b.deviceId(), deviceApps);
        applications.save(app);

        // ---- cash-flow modality
        CashflowFeatures cf = null;
        List<CashflowFeatureService.CategorisedTxn> categorised = List.of();
        if (bankLinked) {
            List<CashflowFeatureService.Txn> txns = req.transactions().stream()
                    .map(t -> new CashflowFeatureService.Txn(t.date(), t.description().trim(), t.amount().doubleValue(),
                            t.balanceAfter() == null ? null : t.balanceAfter().doubleValue()))
                    .toList();
            categorised = cashflow.categorise(txns);
            cf = cashflow.derive(categorised, req.applicant().statedAnnualIncome().doubleValue());
            List<BankTransaction> rows = new ArrayList<>();
            for (CashflowFeatureService.CategorisedTxn ct : categorised) {
                rows.add(new BankTransaction(app.getId(), ct.txn().date(), ct.txn().description(), BigDecimal.valueOf(ct.txn().amount()),
                        ct.txn().balanceAfter() == null ? null : BigDecimal.valueOf(ct.txn().balanceAfter()), ct.category().name(),
                        BigDecimal.valueOf(ct.confidence()).setScale(4, java.math.RoundingMode.HALF_UP)));
            }
            transactions.saveAll(rows);
        }

        // ---- feature vector (bureau + cash-flow + application)
        Map<String, Double> features = featureVector(app, cf);

        // ---- fraud / verification gate (behavioural + veracity signals only)
        Map<String, Double> signals = new HashMap<>();
        signals.put("income_inflation_ratio", cf == null ? null : cf.incomeInflationRatio());
        signals.put("paste_ssn", flag(b.pasteSsn()));
        signals.put("paste_income", flag(b.pasteIncome()));
        signals.put("device_apps_30d", (double) deviceApps);
        signals.put("email_age_days", b.emailAgeDays());
        signals.put("voip_phone", flag(b.voipPhone()));
        signals.put("session_seconds", b.sessionSeconds());
        signals.put("income_field_edits", b.incomeFieldEdits() == null ? null : b.incomeFieldEdits().doubleValue());
        signals.put("bank_linked", bankLinked ? 1.0 : 0.0);
        FraudResult fraud = fraudGate.evaluate(signals);

        // ---- score + policy
        ScoreResult score = engine.score(features);
        DecisionPolicyService.DecisionOutcome outcome = policy.decide(score, fraud, new DecisionPolicyService.Context(
                app.getFileType(), bankLinked, app.getRequestedAmount().doubleValue(), app.getStatedAnnualIncome().doubleValue(),
                cf == null ? null : cf.monthlyIncome()));
        long scoringLatency = (System.nanoTime() - t0) / 1_000_000;

        // ---- similar cohort (advisory) + profile embedding
        String profileText = profiles.build(app.getFileType(), bankLinked, features, app.getRequestedAmount().doubleValue(), app.getStatedAnnualIncome().doubleValue());
        app.setProfileText(profileText);
        similar.storeProfile(app.getId(), profileText);
        SimilarApplicantService.Cohort cohort = similar.findSimilar(profileText, 5);

        // ---- decision record
        Decision decision = new Decision(app.getId(), score.modelId(), score.modelVersion(), fraud.rulesVersion(), fraud.outcome().name(), fraud.points(),
                json.writeValueAsString(fraud.firedRules()), score.pd(), score.score(), outcome.decision().name(), outcome.basis().name(),
                dec(outcome.creditLimit()), dec(outcome.apr()), json.writeValueAsString(score.reasonCodes()), json.writeValueAsString(score.contributions()),
                json.writeValueAsString(features), cf == null ? null : json.writeValueAsString(cf), json.writeValueAsString(outcome.verificationItems()),
                json.writeValueAsString(outcome.policyNotes()), scoringLatency);

        // ---- applicant-facing explanation (guardrailed LLM, template fallback)
        ExplanationService.Inputs inputs = new ExplanationService.Inputs(app.getId(), app.getFileType(), bankLinked,
                app.getRequestedAmount().doubleValue(), app.getStatedAnnualIncome().doubleValue(), score, fraud, outcome, cf, cohort);
        GuardrailService.Guarded notice = explanations.adverseActionNotice(inputs);
        decision.attachNotice(notice.json().toString(), notice.label(), notice.validated(), notice.fallbackUsed());
        decisions.save(decision);

        app.setStatus(switch (outcome.decision()) {
            case REFER -> Application.Status.REFERRED;
            default -> Application.Status.DECIDED;
        });
        applications.save(app);
        log.info("Application {} -> {} ({}), score {}, PD {}, fraud {} - scoring {} ms, notice via {} in {} ms",
                app.getId(), outcome.decision(), outcome.basis(), score.score(), String.format("%.3f", score.pd()), fraud.outcome(),
                scoringLatency, notice.label(), notice.latencyMs());
        return toDetail(app, decision, categorised, cf, cohort, true);
    }

    @Transactional(readOnly = true)
    public ApplicationDetail get(UUID id, boolean fullIdentity) {
        Application app = applications.findById(id).orElseThrow(() -> new NotFoundException("Application " + id));
        Decision d = decisions.findFirstByApplicationIdOrderByCreatedAtDesc(id).orElse(null);
        List<BankTransaction> rows = transactions.findByApplicationIdOrderByPostedDateAsc(id);
        CashflowFeatures cf = d == null || d.getCashflowFeatures() == null ? null : json.readValue(d.getCashflowFeatures(), CashflowFeatures.class);
        SimilarApplicantService.Cohort cohort = app.getProfileText() == null ? null : similar.findSimilar(app.getProfileText(), 5);
        return toDetail(app, d, rows, cf, cohort, fullIdentity);
    }

    /** Lazily generates and caches the underwriter summary - LLM spend only when a human actually opens the case. */
    @Transactional
    public JsonNode underwriterSummary(UUID id) {
        Application app = applications.findById(id).orElseThrow(() -> new NotFoundException("Application " + id));
        Decision d = decisions.findFirstByApplicationIdOrderByCreatedAtDesc(id).orElseThrow(() -> new NotFoundException("Decision for " + id));
        if (d.getSummaryJson() != null) {
            return json.readTree(d.getSummaryJson());
        }
        ScoreResult score = rebuildScore(d);
        FraudResult fraud = rebuildFraud(d);
        DecisionPolicyService.DecisionOutcome outcome = new DecisionPolicyService.DecisionOutcome(
                DecisionPolicyService.Decision.valueOf(d.getDecision()), DecisionPolicyService.Basis.valueOf(d.getBasis()),
                d.getCreditLimit() == null ? null : d.getCreditLimit().doubleValue(), d.getApr() == null ? null : d.getApr().doubleValue(),
                json.readValue(d.getVerificationItems(), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                json.readValue(d.getPolicyNotes(), json.getTypeFactory().constructCollectionType(List.class, String.class)));
        CashflowFeatures cf = d.getCashflowFeatures() == null ? null : json.readValue(d.getCashflowFeatures(), CashflowFeatures.class);
        SimilarApplicantService.Cohort cohort = app.getProfileText() == null ? null : similar.findSimilar(app.getProfileText(), 5);
        GuardrailService.Guarded summary = explanations.underwriterSummary(new ExplanationService.Inputs(app.getId(), app.getFileType(), app.isBankLinked(),
                app.getRequestedAmount().doubleValue(), app.getStatedAnnualIncome().doubleValue(), score, fraud, outcome, cf, cohort));
        d.attachSummary(summary.json().toString(), summary.label());
        decisions.save(d);
        return summary.json();
    }

    @Transactional
    public ApplicationDetail recordAction(UUID id, String username, UnderwriterAction.Action action, String reason) {
        Application app = applications.findById(id).orElseThrow(() -> new NotFoundException("Application " + id));
        actions.save(new UnderwriterAction(id, username, action, reason));
        if (action == UnderwriterAction.Action.APPROVE_OVERRIDE || action == UnderwriterAction.Action.DECLINE_OVERRIDE) {
            app.setStatus(Application.Status.OVERRIDDEN);
        } else if (action == UnderwriterAction.Action.CONFIRM) {
            app.setStatus(Application.Status.DECIDED);
        }
        applications.save(app);
        return get(id, true);
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> list(List<Application.Status> statuses, String onlyCreatedBy) {
        List<Application> apps = onlyCreatedBy != null ? applications.findByCreatedByOrderByCreatedAtDesc(onlyCreatedBy)
                : statuses == null || statuses.isEmpty() ? applications.findAllByOrderByCreatedAtDesc() : applications.findByStatusInOrderByCreatedAtDesc(statuses);
        List<ApplicationSummary> out = new ArrayList<>();
        for (Application a : apps) {
            Decision d = decisions.findFirstByApplicationIdOrderByCreatedAtDesc(a.getId()).orElse(null);
            out.add(new ApplicationSummary(a.getId(), a.getCreatedAt(), a.getStatus().name(), a.getPersonaId(), maskName(a.getFullName()), a.getFileType(),
                    a.isBankLinked(), a.getRequestedAmount(), d == null ? null : d.getDecision(), d == null ? null : d.getBasis(),
                    d == null ? null : d.getScore(), d == null ? null : d.getPd(), d == null ? null : d.getFraudOutcome()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public String ownerOf(UUID id) {
        return applications.findById(id).map(Application::getCreatedBy).orElseThrow(() -> new NotFoundException("Application " + id));
    }

    public record ApplicationSummary(UUID id, Instant createdAt, String status, String personaId, String applicant, String fileType, boolean bankLinked,
                                     BigDecimal requestedAmount, String decision, String basis, Integer score, Double pd, String fraudOutcome) {
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Double> featureVector(Application app, CashflowFeatures cf) {
        Map<String, Double> f = new HashMap<>();
        f.put("bureau_score", dbl(app.getBureauScore()));
        f.put("months_on_file", dbl(app.getMonthsOnFile()));
        f.put("tradelines", dbl(app.getTradelines()));
        f.put("inquiries_6m", dbl(app.getInquiries6m()));
        f.put("delinquencies_24m", dbl(app.getDelinquencies24m()));
        if (cf != null) {
            f.putAll(cf.asFeatureVector());
        }
        double income = Math.max(app.getStatedAnnualIncome().doubleValue(), 1000);
        f.put("amount_to_income", app.getRequestedAmount().doubleValue() / income);
        return f;
    }

    private ApplicationDetail toDetail(Application app, Decision d, List<?> txns, CashflowFeatures cf, SimilarApplicantService.Cohort cohort, boolean fullIdentity) {
        List<ApplicationDetail.TransactionView> tv = new ArrayList<>();
        for (Object o : txns) {
            if (o instanceof BankTransaction t) {
                tv.add(new ApplicationDetail.TransactionView(t.getPostedDate(), t.getDescription(), t.getAmount(), t.getBalanceAfter(), t.getCategory(), t.getCategoryConfidence()));
            } else if (o instanceof CashflowFeatureService.CategorisedTxn ct) {
                tv.add(new ApplicationDetail.TransactionView(ct.txn().date(), ct.txn().description(), BigDecimal.valueOf(ct.txn().amount()),
                        ct.txn().balanceAfter() == null ? null : BigDecimal.valueOf(ct.txn().balanceAfter()), ct.category().name(),
                        BigDecimal.valueOf(ct.confidence()).setScale(4, java.math.RoundingMode.HALF_UP)));
            }
        }
        ApplicationDetail.CashflowView cfView = cf == null ? null : new ApplicationDetail.CashflowView(cashflowMap(cf), tv,
                cf.spendByCategory().entrySet().stream().collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey().name(), e.getValue()), Map::putAll));
        ApplicationDetail.DecisionView dv = d == null ? null : new ApplicationDetail.DecisionView(d.getId(), d.getCreatedAt(), d.getDecision(), d.getBasis(),
                d.getScore(), d.getPd(), d.getCreditLimit(), d.getApr(),
                json.readValue(d.getReasonCodes(), json.getTypeFactory().constructCollectionType(List.class, ScoreResult.ReasonCode.class)),
                json.readValue(d.getContributions(), json.getTypeFactory().constructCollectionType(List.class, ScoreResult.FeatureContribution.class)),
                json.readValue(d.getVerificationItems(), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                json.readValue(d.getPolicyNotes(), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                new ApplicationDetail.FraudView(d.getFraudOutcome(), d.getFraudPoints(),
                        json.readValue(d.getFraudRulesFired(), json.getTypeFactory().constructCollectionType(List.class, Map.class)), d.getFraudRulesVersion()),
                d.getNoticeJson() == null ? null : json.readTree(d.getNoticeJson()), d.getNoticeProvider(), d.getNoticeValidated(), d.getNoticeFallbackUsed(),
                d.getSummaryJson() == null ? null : json.readTree(d.getSummaryJson()), d.getSummaryProvider(), d.getModelId(), d.getModelVersion(), d.getScoringLatencyMs());
        List<ApplicationDetail.ActionView> av = actions.findByApplicationIdOrderByCreatedAtAsc(app.getId()).stream()
                .map(a -> new ApplicationDetail.ActionView(a.getCreatedAt(), a.getUsername(), a.getAction().name(), a.getReason())).toList();
        return new ApplicationDetail(app.getId(), app.getCreatedAt(), app.getStatus().name(), app.getPersonaId(),
                new ApplicationDetail.ApplicantView(fullIdentity ? app.getFullName() : maskName(app.getFullName()), fullIdentity ? app.getEmail() : maskEmail(app.getEmail()),
                        fullIdentity ? app.getPhone() : null, app.getEmploymentType().name(), app.getStatedAnnualIncome(), app.getNationalIdHash() != null),
                new ApplicationDetail.LoanView(app.getRequestedAmount(), app.getLoanPurpose()), app.getFileType(), app.isBankLinked(),
                new ApplicationDetail.BureauView(dbl(app.getBureauScore()), dbl(app.getMonthsOnFile()), dbl(app.getTradelines()), dbl(app.getInquiries6m()), dbl(app.getDelinquencies24m())),
                new ApplicationDetail.BehaviourView(dbl(app.getSessionSeconds()), app.getIncomeFieldEdits(), app.getPasteSsn(), app.getPasteIncome(), dbl(app.getEmailAgeDays()),
                        app.getVoipPhone(), app.getDeviceId(), app.getDeviceApps30d()),
                dv, cfView, cohort, av);
    }

    private ScoreResult rebuildScore(Decision d) {
        Map<String, Double> features = json.readValue(d.getFeaturesSnapshot(), json.getTypeFactory().constructMapType(Map.class, String.class, Double.class));
        return engine.score(features);
    }

    private FraudResult rebuildFraud(Decision d) {
        List<FraudResult.FiredRule> fired = json.readValue(d.getFraudRulesFired(), json.getTypeFactory().constructCollectionType(List.class, FraudResult.FiredRule.class));
        return new FraudResult(FraudResult.Outcome.valueOf(d.getFraudOutcome()), d.getFraudPoints(), fired, d.getFraudRulesVersion());
    }

    private static Map<String, Object> cashflowMap(CashflowFeatures cf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monthsObserved", cf.monthsObserved());
        m.put("monthlyIncome", cf.monthlyIncome());
        m.put("incomeCv", cf.incomeCv());
        m.put("balanceTrend", cf.balanceTrend());
        m.put("minBalanceRatio", cf.minBalanceRatio());
        m.put("nsfCount", cf.nsfCount());
        m.put("rentOntimeRatio", cf.rentOntimeRatio());
        m.put("utilityOntimeRatio", cf.utilityOntimeRatio());
        m.put("telcoOntimeRatio", cf.telcoOntimeRatio());
        m.put("obligationRatio", cf.obligationRatio());
        m.put("discretionaryRatio", cf.discretionaryRatio());
        m.put("gamblingFlag", cf.gamblingFlag());
        m.put("incomeInflationRatio", cf.incomeInflationRatio());
        return m;
    }

    static String maskName(String name) {
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(' ').append(parts[i].charAt(0)).append('.');
        }
        return sb.toString();
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private static BigDecimal dec(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    private static Double dbl(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static Double flag(Boolean b) {
        return b == null ? null : (b ? 1.0 : 0.0);
    }
}
