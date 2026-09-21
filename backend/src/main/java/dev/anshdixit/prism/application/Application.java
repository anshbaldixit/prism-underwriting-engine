package dev.anshdixit.prism.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A credit application: identity (minimised), what was asked for, what the bureau returned, and the
 * behavioural signals captured while the form was filled in. The vector column {@code profile_embedding}
 * is written through JDBC, not JPA, so it is deliberately unmapped here.
 */
@Entity
@Table(name = "applications")
public class Application {

    public enum Status { RECEIVED, DECIDED, REFERRED, OVERRIDDEN }

    public enum EmploymentType { FULL_TIME, PART_TIME, SELF_EMPLOYED, STUDENT, UNEMPLOYED, RETIRED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(length = 64)
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RECEIVED;

    @Column(length = 64)
    private String personaId;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(length = 64)
    private String nationalIdHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmploymentType employmentType;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal statedAnnualIncome;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal requestedAmount;

    @Column(length = 300)
    private String loanPurpose;

    @Column(nullable = false)
    private boolean consentBankData;

    @Column(nullable = false)
    private boolean consentAltData;

    @Column(nullable = false)
    private boolean bankLinked;

    @Column(nullable = false, length = 10)
    private String fileType;

    @Column(precision = 6, scale = 2)
    private BigDecimal bureauScore;

    @Column(precision = 8, scale = 2)
    private BigDecimal monthsOnFile;

    @Column(precision = 6, scale = 2)
    private BigDecimal tradelines;

    @Column(precision = 6, scale = 2)
    private BigDecimal inquiries6m;

    @Column(precision = 6, scale = 2)
    private BigDecimal delinquencies24m;

    @Column(precision = 10, scale = 1)
    private BigDecimal sessionSeconds;

    private Integer incomeFieldEdits;

    private Boolean pasteSsn;

    private Boolean pasteIncome;

    @Column(precision = 10, scale = 1)
    private BigDecimal emailAgeDays;

    private Boolean voipPhone;

    @Column(length = 80)
    private String deviceId;

    private Integer deviceApps30d;

    @Column(columnDefinition = "text")
    private String profileText;

    protected Application() {
    }

    public Application(String createdBy, String personaId, String fullName, String email, String phone, String nationalIdHash,
                       EmploymentType employmentType, BigDecimal statedAnnualIncome, BigDecimal requestedAmount, String loanPurpose,
                       boolean consentBankData, boolean consentAltData, boolean bankLinked, String fileType) {
        this.createdBy = createdBy;
        this.personaId = personaId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.nationalIdHash = nationalIdHash;
        this.employmentType = employmentType;
        this.statedAnnualIncome = statedAnnualIncome;
        this.requestedAmount = requestedAmount;
        this.loanPurpose = loanPurpose;
        this.consentBankData = consentBankData;
        this.consentAltData = consentAltData;
        this.bankLinked = bankLinked;
        this.fileType = fileType;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void setBureau(BigDecimal bureauScore, BigDecimal monthsOnFile, BigDecimal tradelines, BigDecimal inquiries6m, BigDecimal delinquencies24m) {
        this.bureauScore = bureauScore;
        this.monthsOnFile = monthsOnFile;
        this.tradelines = tradelines;
        this.inquiries6m = inquiries6m;
        this.delinquencies24m = delinquencies24m;
    }

    public void setBehaviour(BigDecimal sessionSeconds, Integer incomeFieldEdits, Boolean pasteSsn, Boolean pasteIncome,
                             BigDecimal emailAgeDays, Boolean voipPhone, String deviceId, Integer deviceApps30d) {
        this.sessionSeconds = sessionSeconds;
        this.incomeFieldEdits = incomeFieldEdits;
        this.pasteSsn = pasteSsn;
        this.pasteIncome = pasteIncome;
        this.emailAgeDays = emailAgeDays;
        this.voipPhone = voipPhone;
        this.deviceId = deviceId;
        this.deviceApps30d = deviceApps30d;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public Status getStatus() { return status; }
    public String getPersonaId() { return personaId; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getNationalIdHash() { return nationalIdHash; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public BigDecimal getStatedAnnualIncome() { return statedAnnualIncome; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public String getLoanPurpose() { return loanPurpose; }
    public boolean isConsentBankData() { return consentBankData; }
    public boolean isConsentAltData() { return consentAltData; }
    public boolean isBankLinked() { return bankLinked; }
    public String getFileType() { return fileType; }
    public BigDecimal getBureauScore() { return bureauScore; }
    public BigDecimal getMonthsOnFile() { return monthsOnFile; }
    public BigDecimal getTradelines() { return tradelines; }
    public BigDecimal getInquiries6m() { return inquiries6m; }
    public BigDecimal getDelinquencies24m() { return delinquencies24m; }
    public BigDecimal getSessionSeconds() { return sessionSeconds; }
    public Integer getIncomeFieldEdits() { return incomeFieldEdits; }
    public Boolean getPasteSsn() { return pasteSsn; }
    public Boolean getPasteIncome() { return pasteIncome; }
    public BigDecimal getEmailAgeDays() { return emailAgeDays; }
    public Boolean getVoipPhone() { return voipPhone; }
    public String getDeviceId() { return deviceId; }
    public Integer getDeviceApps30d() { return deviceApps30d; }
    public String getProfileText() { return profileText; }

    public void setStatus(Status status) { this.status = status; }
    public void setProfileText(String profileText) { this.profileText = profileText; }
}
