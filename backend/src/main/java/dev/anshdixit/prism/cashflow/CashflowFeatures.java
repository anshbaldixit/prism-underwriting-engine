package dev.anshdixit.prism.cashflow;

import java.util.HashMap;
import java.util.Map;

/**
 * Underwriting features derived from a linked account. Names match the scorecard's feature catalogue
 * exactly so the vector can be handed straight to {@code ScorecardEngine}. Regularity ratios are null when a
 * bill type never appears in the statement (e.g. no rent because the applicant lives with family) - the
 * scorecard treats that as "not observed", never as "paid late".
 */
public record CashflowFeatures(
        int monthsObserved,
        double monthlyIncome,
        double incomeCv,
        double balanceTrend,
        double minBalanceRatio,
        int nsfCount,
        Double rentOntimeRatio,
        Double utilityOntimeRatio,
        Double telcoOntimeRatio,
        double obligationRatio,
        double discretionaryRatio,
        boolean gamblingFlag,
        Double incomeInflationRatio,
        Map<TransactionCategory, Double> spendByCategory) {

    public Map<String, Double> asFeatureVector() {
        Map<String, Double> f = new HashMap<>();
        f.put("monthly_income", monthlyIncome);
        f.put("income_cv", incomeCv);
        f.put("income_months_observed", (double) monthsObserved);
        f.put("balance_trend", balanceTrend);
        f.put("min_balance_ratio", minBalanceRatio);
        f.put("nsf_count_6m", (double) nsfCount);
        f.put("rent_ontime_ratio", rentOntimeRatio);
        f.put("utility_ontime_ratio", utilityOntimeRatio);
        f.put("telco_ontime_ratio", telcoOntimeRatio);
        f.put("obligation_ratio", obligationRatio);
        f.put("discretionary_ratio", discretionaryRatio);
        f.put("gambling_flag", gamblingFlag ? 1.0 : 0.0);
        return f;
    }
}
