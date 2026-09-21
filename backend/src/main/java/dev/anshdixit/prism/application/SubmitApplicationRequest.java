package dev.anshdixit.prism.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inbound application payload. Every field is bounded and validated before any business logic runs.
 * In this prototype the bureau block and the transaction feed are supplied by the client (simulating the
 * bureau pull and the open-banking connection); in production both are fetched server-side from the
 * providers after consent, and the shape below is what those adapters would return.
 */
public record SubmitApplicationRequest(
        @Valid @NotNull Applicant applicant,
        @Valid @NotNull Loan loan,
        @Valid @NotNull Consent consent,
        @Valid @NotNull Bureau bureau,
        @Valid Behaviour behaviour,
        @Valid @Size(max = 2000) List<Transaction> transactions,
        @Size(max = 64) String personaId) {

    public record Applicant(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 40) String phone,
            @Pattern(regexp = "^$|\\d{3}-\\d{2}-\\d{4}|\\d{9}|\\d{12}", message = "nationalId must be 9 or 12 digits") String nationalId,
            @NotNull Application.EmploymentType employmentType,
            @NotNull @DecimalMin("0") @DecimalMax("10000000") BigDecimal statedAnnualIncome) {
    }

    public record Loan(
            @NotNull @DecimalMin("300") @DecimalMax("20000") BigDecimal requestedAmount,
            @Size(max = 300) String purpose) {
    }

    public record Consent(boolean bankData, boolean altData) {
    }

    public record Bureau(
            @NotBlank @Pattern(regexp = "thick|thin|ntc") String fileType,
            @DecimalMin("300") @DecimalMax("850") Double bureauScore,
            @DecimalMin("0") @DecimalMax("600") Double monthsOnFile,
            @DecimalMin("0") @DecimalMax("100") Double tradelines,
            @DecimalMin("0") @DecimalMax("100") Double inquiries6m,
            @DecimalMin("0") @DecimalMax("100") Double delinquencies24m) {
    }

    public record Behaviour(
            @DecimalMin("0") @DecimalMax("86400") Double sessionSeconds,
            @Min(0) @Max(1000) Integer incomeFieldEdits,
            Boolean pasteSsn,
            Boolean pasteIncome,
            @DecimalMin("0") @DecimalMax("36500") Double emailAgeDays,
            Boolean voipPhone,
            @Size(max = 80) String deviceId,
            @Min(0) @Max(100) Integer deviceAppsLast30dSeed) {
    }

    public record Transaction(
            @NotNull LocalDate date,
            @NotBlank @Size(max = 200) String description,
            @NotNull @DecimalMin("-1000000") @DecimalMax("1000000") BigDecimal amount,
            @DecimalMin("-1000000") @DecimalMax("10000000") BigDecimal balanceAfter) {
    }
}
