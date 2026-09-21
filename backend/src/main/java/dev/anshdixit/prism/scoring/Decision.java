package dev.anshdixit.prism.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of one scoring pass. Everything needed to reproduce and explain the decision later is
 * snapshotted here - model version, the exact feature values, each contribution, the reasons, the notice -
 * because a regulator asks "why was this customer declined on that day", not "what would the model say now".
 */
@Entity
@Table(name = "decisions")
public class Decision {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 60)
    private String modelId;

    @Column(nullable = false, length = 20)
    private String modelVersion;

    @Column(nullable = false, length = 20)
    private String fraudRulesVersion;

    @Column(nullable = false, length = 10)
    private String fraudOutcome;

    @Column(nullable = false)
    private int fraudPoints;

    @Column(nullable = false, columnDefinition = "text")
    private String fraudRulesFired;

    @Column(nullable = false)
    private double pd;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false, length = 10)
    private String decision;

    @Column(nullable = false, length = 30)
    private String basis;

    @Column(precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(precision = 6, scale = 2)
    private BigDecimal apr;

    @Column(nullable = false, columnDefinition = "text")
    private String reasonCodes;

    @Column(nullable = false, columnDefinition = "text")
    private String contributions;

    @Column(nullable = false, columnDefinition = "text")
    private String featuresSnapshot;

    @Column(columnDefinition = "text")
    private String cashflowFeatures;

    @Column(nullable = false, columnDefinition = "text")
    private String verificationItems;

    @Column(nullable = false, columnDefinition = "text")
    private String policyNotes;

    @Column(columnDefinition = "text")
    private String noticeJson;

    @Column(length = 120)
    private String noticeProvider;

    private Boolean noticeValidated;

    private Boolean noticeFallbackUsed;

    @Column(columnDefinition = "text")
    private String summaryJson;

    @Column(length = 120)
    private String summaryProvider;

    @Column(nullable = false)
    private long scoringLatencyMs;

    protected Decision() {
    }

    public Decision(UUID applicationId, String modelId, String modelVersion, String fraudRulesVersion, String fraudOutcome, int fraudPoints,
                    String fraudRulesFired, double pd, int score, String decision, String basis, BigDecimal creditLimit, BigDecimal apr,
                    String reasonCodes, String contributions, String featuresSnapshot, String cashflowFeatures, String verificationItems,
                    String policyNotes, long scoringLatencyMs) {
        this.applicationId = applicationId;
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.fraudRulesVersion = fraudRulesVersion;
        this.fraudOutcome = fraudOutcome;
        this.fraudPoints = fraudPoints;
        this.fraudRulesFired = fraudRulesFired;
        this.pd = pd;
        this.score = score;
        this.decision = decision;
        this.basis = basis;
        this.creditLimit = creditLimit;
        this.apr = apr;
        this.reasonCodes = reasonCodes;
        this.contributions = contributions;
        this.featuresSnapshot = featuresSnapshot;
        this.cashflowFeatures = cashflowFeatures;
        this.verificationItems = verificationItems;
        this.policyNotes = policyNotes;
        this.scoringLatencyMs = scoringLatencyMs;
    }

    public void attachNotice(String json, String provider, boolean validated, boolean fallbackUsed) {
        this.noticeJson = json;
        this.noticeProvider = provider;
        this.noticeValidated = validated;
        this.noticeFallbackUsed = fallbackUsed;
    }

    public void attachSummary(String json, String provider) {
        this.summaryJson = json;
        this.summaryProvider = provider;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getModelId() { return modelId; }
    public String getModelVersion() { return modelVersion; }
    public String getFraudRulesVersion() { return fraudRulesVersion; }
    public String getFraudOutcome() { return fraudOutcome; }
    public int getFraudPoints() { return fraudPoints; }
    public String getFraudRulesFired() { return fraudRulesFired; }
    public double getPd() { return pd; }
    public int getScore() { return score; }
    public String getDecision() { return decision; }
    public String getBasis() { return basis; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public BigDecimal getApr() { return apr; }
    public String getReasonCodes() { return reasonCodes; }
    public String getContributions() { return contributions; }
    public String getFeaturesSnapshot() { return featuresSnapshot; }
    public String getCashflowFeatures() { return cashflowFeatures; }
    public String getVerificationItems() { return verificationItems; }
    public String getPolicyNotes() { return policyNotes; }
    public String getNoticeJson() { return noticeJson; }
    public String getNoticeProvider() { return noticeProvider; }
    public Boolean getNoticeValidated() { return noticeValidated; }
    public Boolean getNoticeFallbackUsed() { return noticeFallbackUsed; }
    public String getSummaryJson() { return summaryJson; }
    public String getSummaryProvider() { return summaryProvider; }
    public long getScoringLatencyMs() { return scoringLatencyMs; }
}
