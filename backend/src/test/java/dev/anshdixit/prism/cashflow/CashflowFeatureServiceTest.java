package dev.anshdixit.prism.cashflow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CashflowFeatureServiceTest {

    /** Keyword categoriser standing in for the pgvector one - the feature arithmetic is what is under test. */
    static final TransactionCategorizer FAKE = descriptions -> {
        Map<String, TransactionCategorizer.Categorization> out = new HashMap<>();
        for (String d : descriptions) {
            TransactionCategory c = d.contains("PAYROLL") ? TransactionCategory.INCOME_PAYROLL
                    : d.contains("RENT") ? TransactionCategory.RENT
                    : d.contains("ENERGY") ? TransactionCategory.UTILITY
                    : d.contains("MOBILE") ? TransactionCategory.TELCO
                    : d.contains("LOAN") ? TransactionCategory.LOAN_PAYMENT
                    : d.contains("NSF") ? TransactionCategory.NSF_FEE
                    : d.contains("DRAFTKINGS") ? TransactionCategory.GAMBLING
                    : d.contains("DOORDASH") ? TransactionCategory.DINING
                    : TransactionCategory.GROCERY;
            out.put(d, new TransactionCategorizer.Categorization(c, 0.9, d));
        }
        return out;
    };

    final CashflowFeatureService service = new CashflowFeatureService(FAKE);

    private static CashflowFeatureService.Txn t(String date, String desc, double amount, double balance) {
        return new CashflowFeatureService.Txn(LocalDate.parse(date), desc, amount, balance);
    }

    @Test
    void derivesIncomeRegularityAndRatiosFromCategorisedStatement() {
        List<CashflowFeatureService.Txn> txns = List.of(
                t("2026-06-01", "ACME PAYROLL", 2000, 2500), t("2026-06-02", "ZELLE RENT", -900, 1600), t("2026-06-10", "CITY ENERGY", -100, 1500),
                t("2026-06-12", "T-MOBILE", -50, 1450), t("2026-06-15", "AUTO LOAN", -300, 1150), t("2026-06-20", "KROGER", -150, 1000),
                t("2026-07-01", "ACME PAYROLL", 2000, 3000), t("2026-07-02", "ZELLE RENT", -900, 2100), t("2026-07-12", "T-MOBILE", -50, 2050),
                t("2026-07-15", "AUTO LOAN", -300, 1750), t("2026-07-18", "NSF FEE", -34, 1716), t("2026-07-20", "DOORDASH", -40, 1676),
                t("2026-08-01", "ACME PAYROLL", 2000, 3676), t("2026-08-02", "ZELLE RENT", -900, 2776), t("2026-08-10", "CITY ENERGY", -100, 2676),
                t("2026-08-12", "T-MOBILE", -50, 2626), t("2026-08-15", "AUTO LOAN", -300, 2326), t("2026-08-21", "DRAFTKINGS", -60, 2266));
        CashflowFeatures f = service.derive(service.categorise(txns), 24000.0);

        assertThat(f.monthsObserved()).isEqualTo(3);
        assertThat(f.monthlyIncome()).isEqualTo(2000.0);
        assertThat(f.incomeCv()).isEqualTo(0.02); // perfectly regular income clamps to the floor
        assertThat(f.rentOntimeRatio()).isEqualTo(1.0);
        assertThat(f.utilityOntimeRatio()).isCloseTo(2.0 / 3.0, within(1e-3));
        assertThat(f.telcoOntimeRatio()).isEqualTo(1.0);
        assertThat(f.nsfCount()).isEqualTo(1);
        assertThat(f.gamblingFlag()).isTrue();
        assertThat(f.obligationRatio()).isCloseTo(300.0 / 2000.0, within(1e-6));
        assertThat(f.incomeInflationRatio()).isCloseTo(1.0, within(1e-6));
        assertThat(f.balanceTrend()).isGreaterThan(0);
        assertThat(f.minBalanceRatio()).isCloseTo(1000.0 / 2000.0, within(1e-6));
        assertThat(f.asFeatureVector()).containsKeys("monthly_income", "income_cv", "nsf_count_6m", "rent_ontime_ratio", "gambling_flag");
    }

    @Test
    void billNeverSeenIsNotObservedRatherThanLate() {
        List<CashflowFeatureService.Txn> txns = List.of(t("2026-06-01", "ACME PAYROLL", 1500, 1500), t("2026-07-01", "ACME PAYROLL", 1500, 3000),
                t("2026-08-01", "ACME PAYROLL", 1500, 4500));
        CashflowFeatures f = service.derive(service.categorise(txns), 18000.0);
        assertThat(f.rentOntimeRatio()).isNull();
        assertThat(f.asFeatureVector().get("rent_ontime_ratio")).isNull();
    }

    @Test
    void creditsAreNeverClassifiedAsSpendAndDebitsNeverAsIncome() {
        List<CashflowFeatureService.CategorisedTxn> c = service.categorise(List.of(
                t("2026-06-01", "KROGER REFUND", 25, 100), t("2026-06-02", "ACME PAYROLL", -10, 90)));
        assertThat(c.get(0).category().isIncome()).isTrue();
        assertThat(c.get(1).category().isIncome()).isFalse();
    }

    @Test
    void statedIncomeFarAboveVerifiedProducesInflationRatio() {
        List<CashflowFeatureService.Txn> txns = List.of(t("2026-06-01", "ACME PAYROLL", 3000, 3000), t("2026-07-01", "ACME PAYROLL", 3000, 6000),
                t("2026-08-01", "ACME PAYROLL", 3000, 9000));
        CashflowFeatures f = service.derive(service.categorise(txns), 90000.0);
        assertThat(f.incomeInflationRatio()).isCloseTo(2.5, within(1e-6));
    }

    @SuppressWarnings("unused")
    private static Collection<String> unused() {
        return List.of();
    }
}
