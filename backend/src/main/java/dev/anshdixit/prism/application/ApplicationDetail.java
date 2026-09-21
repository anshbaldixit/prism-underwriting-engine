package dev.anshdixit.prism.application;

import dev.anshdixit.prism.scoring.ScoreResult;
import dev.anshdixit.prism.similar.SimilarApplicantService;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read model returned by the API. Identity fields are masked according to the caller's role. */
public record ApplicationDetail(
        UUID id,
        Instant createdAt,
        String status,
        String personaId,
        ApplicantView applicant,
        LoanView loan,
        String fileType,
        boolean bankLinked,
        BureauView bureau,
        BehaviourView behaviour,
        DecisionView decision,
        CashflowView cashflow,
        SimilarApplicantService.Cohort similar,
        List<ActionView> actions) {

    public record ApplicantView(String fullName, String email, String phone, String employmentType, BigDecimal statedAnnualIncome, boolean nationalIdOnFile) {
    }

    public record LoanView(BigDecimal requestedAmount, String purpose) {
    }

    public record BureauView(Double bureauScore, Double monthsOnFile, Double tradelines, Double inquiries6m, Double delinquencies24m) {
    }

    public record BehaviourView(Double sessionSeconds, Integer incomeFieldEdits, Boolean pasteSsn, Boolean pasteIncome, Double emailAgeDays,
                                Boolean voipPhone, String deviceId, Integer deviceApps30d) {
    }

    public record DecisionView(
            UUID id,
            Instant createdAt,
            String decision,
            String basis,
            int score,
            double pd,
            BigDecimal creditLimit,
            BigDecimal apr,
            List<ScoreResult.ReasonCode> reasonCodes,
            List<ScoreResult.FeatureContribution> contributions,
            List<String> verificationItems,
            List<String> policyNotes,
            FraudView fraud,
            JsonNode notice,
            String noticeProvider,
            Boolean noticeValidated,
            Boolean noticeFallbackUsed,
            JsonNode summary,
            String summaryProvider,
            String modelId,
            String modelVersion,
            long scoringLatencyMs) {
    }

    public record FraudView(String outcome, int points, List<Map<String, Object>> firedRules, String rulesVersion) {
    }

    public record CashflowView(Map<String, Object> features, List<TransactionView> transactions, Map<String, Double> spendByCategory) {
    }

    public record TransactionView(LocalDate date, String description, BigDecimal amount, BigDecimal balanceAfter, String category, BigDecimal confidence) {
    }

    public record ActionView(Instant createdAt, String username, String action, String reason) {
    }
}
