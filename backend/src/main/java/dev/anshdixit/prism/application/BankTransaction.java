package dev.anshdixit.prism.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bank_transactions")
public class BankTransaction {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private LocalDate postedDate;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(precision = 14, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 30)
    private String category;

    @Column(precision = 5, scale = 4)
    private BigDecimal categoryConfidence;

    protected BankTransaction() {
    }

    public BankTransaction(UUID applicationId, LocalDate postedDate, String description, BigDecimal amount, BigDecimal balanceAfter,
                           String category, BigDecimal categoryConfidence) {
        this.applicationId = applicationId;
        this.postedDate = postedDate;
        this.description = description;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.category = category;
        this.categoryConfidence = categoryConfidence;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public LocalDate getPostedDate() { return postedDate; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getCategory() { return category; }
    public BigDecimal getCategoryConfidence() { return categoryConfidence; }
}
