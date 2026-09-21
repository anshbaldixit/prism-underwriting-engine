package dev.anshdixit.prism.underwriting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Human-in-the-loop record. Overrides feed the retraining set: the model learns from underwriters, not the reverse. */
@Entity
@Table(name = "underwriter_actions")
public class UnderwriterAction {

    public enum Action { CONFIRM, APPROVE_OVERRIDE, DECLINE_OVERRIDE, REQUEST_DOCS }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 64)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    @Column(nullable = false, length = 600)
    private String reason;

    protected UnderwriterAction() {
    }

    public UnderwriterAction(UUID applicationId, String username, Action action, String reason) {
        this.applicationId = applicationId;
        this.username = username;
        this.action = action;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUsername() { return username; }
    public Action getAction() { return action; }
    public String getReason() { return reason; }
}
