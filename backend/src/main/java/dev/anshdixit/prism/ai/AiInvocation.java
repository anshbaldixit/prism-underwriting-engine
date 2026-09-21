package dev.anshdixit.prism.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record for every model call: what was asked (by hash, never the prompt itself), which provider and
 * model answered, how long it took, and whether the output passed the guardrails. This is the evidence trail
 * a model-risk or compliance reviewer asks for first.
 */
@Entity
@Table(name = "ai_invocations")
public class AiInvocation {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private String task;

    @Column(nullable = false)
    private String provider;

    private String model;

    private UUID applicationId;

    @Column(nullable = false, length = 64)
    private String promptSha256;

    private Integer inputTokens;

    private Integer outputTokens;

    private long latencyMs;

    private boolean outputValid;

    private boolean fallbackUsed;

    private String guardrailAction;

    @Column(columnDefinition = "text")
    private String validationErrors;

    protected AiInvocation() {
    }

    public AiInvocation(String task, String provider, String model, UUID applicationId, String promptSha256) {
        this.task = task;
        this.provider = provider;
        this.model = model;
        this.applicationId = applicationId;
        this.promptSha256 = promptSha256;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getTask() { return task; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public UUID getApplicationId() { return applicationId; }
    public String getPromptSha256() { return promptSha256; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isOutputValid() { return outputValid; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public String getGuardrailAction() { return guardrailAction; }
    public String getValidationErrors() { return validationErrors; }

    public void setProvider(String provider) { this.provider = provider; }
    public void setModel(String model) { this.model = model; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public void setOutputValid(boolean outputValid) { this.outputValid = outputValid; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }
    public void setGuardrailAction(String guardrailAction) { this.guardrailAction = guardrailAction; }
    public void setValidationErrors(String validationErrors) { this.validationErrors = validationErrors; }
}
