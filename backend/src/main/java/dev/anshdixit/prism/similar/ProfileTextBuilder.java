package dev.anshdixit.prism.similar;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders a feature vector as a short, bucketed description ("income stable; rent regular; no overdrafts").
 * Both historical applicants and live applications are embedded from this same vocabulary, so nearest-neighbour
 * search compares like with like regardless of the embedding provider. Contains no identity information.
 */
@Component
public class ProfileTextBuilder {

    public String build(String fileType, boolean bankLinked, Map<String, Double> f, Double requestedAmount, Double statedAnnualIncome) {
        StringBuilder sb = new StringBuilder();
        sb.append("credit file ").append(switch (fileType) { case "ntc" -> "none new-to-credit"; case "thin" -> "thin limited history"; default -> "thick established"; });
        sb.append("; bureau score ").append(bucket(f.get("bureau_score"), new double[]{600, 660, 720}, new String[]{"low", "fair", "good", "excellent"}, "not available"));
        sb.append("; history ").append(bucket(f.get("months_on_file"), new double[]{12, 36, 120}, new String[]{"very short", "short", "medium", "long"}, "none"));
        sb.append("; delinquencies ").append(bucket(f.get("delinquencies_24m"), new double[]{1, 2}, new String[]{"none", "one", "several"}, "unknown"));
        sb.append("; bank data ").append(bankLinked ? "linked" : "not linked");
        if (bankLinked) {
            sb.append("; income ").append(bucket(f.get("monthly_income"), new double[]{2000, 3500, 6000}, new String[]{"low", "modest", "mid", "high"}, "unknown"));
            sb.append("; income stability ").append(bucket(f.get("income_cv"), new double[]{0.15, 0.35, 0.6}, new String[]{"very stable", "stable", "variable", "volatile"}, "unknown"));
            sb.append("; balance trend ").append(bucket(f.get("balance_trend"), new double[]{-0.02, 0.02}, new String[]{"falling", "flat", "rising"}, "unknown"));
            sb.append("; balance buffer ").append(bucket(f.get("min_balance_ratio"), new double[]{0.0, 0.25, 0.75}, new String[]{"overdrawn", "thin", "adequate", "strong"}, "unknown"));
            sb.append("; overdrafts ").append(bucket(f.get("nsf_count_6m"), new double[]{1, 3}, new String[]{"none", "few", "frequent"}, "unknown"));
            sb.append("; rent payments ").append(regular(f.get("rent_ontime_ratio")));
            sb.append("; utility payments ").append(regular(f.get("utility_ontime_ratio")));
            sb.append("; phone payments ").append(regular(f.get("telco_ontime_ratio")));
            sb.append("; obligations ").append(bucket(f.get("obligation_ratio"), new double[]{0.2, 0.4}, new String[]{"light", "moderate", "heavy"}, "unknown"));
            sb.append("; discretionary spend ").append(bucket(f.get("discretionary_ratio"), new double[]{0.2, 0.4}, new String[]{"low", "moderate", "high"}, "unknown"));
            sb.append("; gambling ").append(f.get("gambling_flag") != null && f.get("gambling_flag") > 0 ? "present" : "none");
        }
        if (requestedAmount != null && statedAnnualIncome != null && statedAnnualIncome > 0) {
            sb.append("; requested vs income ").append(bucket(requestedAmount / statedAnnualIncome, new double[]{0.05, 0.15, 0.3}, new String[]{"small", "moderate", "large", "very large"}, "unknown"));
        }
        return sb.toString();
    }

    private static String regular(Double ratio) {
        if (ratio == null) {
            return "not observed";
        }
        return ratio >= 0.95 ? "regular" : ratio >= 0.6 ? "mostly regular" : "irregular";
    }

    private static String bucket(Double v, double[] edges, String[] labels, String missing) {
        if (v == null || v.isNaN()) {
            return missing;
        }
        for (int i = 0; i < edges.length; i++) {
            if (v < edges[i]) {
                return labels[i];
            }
        }
        return labels[edges.length];
    }
}
