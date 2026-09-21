package dev.anshdixit.prism.cashflow;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives the cash-flow feature vector from categorised transactions. Pure arithmetic over the statement,
 * so every number in the feature table can be traced back to specific transactions in the UI.
 */
@Service
public class CashflowFeatureService {

    public record Txn(LocalDate date, String description, double amount, Double balanceAfter) {
    }

    public record CategorisedTxn(Txn txn, TransactionCategory category, double confidence) {
    }

    private final TransactionCategorizer categorizer;

    public CashflowFeatureService(TransactionCategorizer categorizer) {
        this.categorizer = categorizer;
    }

    public List<CategorisedTxn> categorise(List<Txn> txns) {
        Map<String, TransactionCategorizer.Categorization> byDesc =
                categorizer.categorize(txns.stream().map(Txn::description).distinct().toList());
        List<CategorisedTxn> out = new ArrayList<>(txns.size());
        for (Txn t : txns) {
            TransactionCategorizer.Categorization c = byDesc.get(t.description());
            TransactionCategory cat = c == null ? TransactionCategory.OTHER : c.category();
            // Guard against semantic mismatches: a credit can only be income/transfer, a debit never income.
            if (t.amount() > 0 && !cat.isIncome()) {
                cat = TransactionCategory.INCOME_TRANSFER;
            } else if (t.amount() < 0 && cat.isIncome()) {
                cat = TransactionCategory.OTHER;
            }
            out.add(new CategorisedTxn(t, cat, c == null ? 0 : c.confidence()));
        }
        return out;
    }

    public CashflowFeatures derive(List<CategorisedTxn> txns, Double statedAnnualIncome) {
        if (txns.isEmpty()) {
            throw new IllegalArgumentException("No transactions to derive cash-flow features from");
        }
        TreeMap<YearMonth, MonthAgg> months = new TreeMap<>();
        Map<TransactionCategory, Double> spendByCategory = new EnumMap<>(TransactionCategory.class);
        double minBalance = Double.POSITIVE_INFINITY;
        int nsf = 0;
        boolean gambling = false;

        for (CategorisedTxn ct : txns) {
            Txn t = ct.txn();
            MonthAgg m = months.computeIfAbsent(YearMonth.from(t.date()), k -> new MonthAgg());
            if (t.amount() > 0 && ct.category().isIncome()) {
                m.income += t.amount();
            } else if (t.amount() < 0) {
                double debit = -t.amount();
                m.debits += debit;
                spendByCategory.merge(ct.category(), debit, Double::sum);
                switch (ct.category().kind()) {
                    case OBLIGATION -> m.obligations += debit;
                    case DISCRETIONARY -> m.discretionary += debit;
                    default -> { }
                }
                switch (ct.category()) {
                    case RENT -> m.rentPaid = true;
                    case UTILITY -> m.utilityPaid = true;
                    case TELCO -> m.telcoPaid = true;
                    case NSF_FEE -> nsf++;
                    case GAMBLING -> gambling = true;
                    default -> { }
                }
            }
            if (t.balanceAfter() != null) {
                minBalance = Math.min(minBalance, t.balanceAfter());
                m.lastBalance = t.balanceAfter();
            }
        }

        int n = months.size();
        double[] incomes = months.values().stream().mapToDouble(m -> m.income).toArray();
        double meanIncome = mean(incomes);
        double cv = meanIncome > 0 ? std(incomes) / meanIncome : 1.2;
        int monthsWithIncome = (int) months.values().stream().filter(m -> m.income > 0).count();

        double obligations = mean(months.values().stream().mapToDouble(m -> m.obligations).toArray());
        double totalDebits = months.values().stream().mapToDouble(m -> m.debits).sum();
        double discretionary = months.values().stream().mapToDouble(m -> m.discretionary).sum();

        List<Double> endBalances = months.values().stream().filter(m -> m.lastBalance != null).map(m -> m.lastBalance).toList();
        double trend = 0.0;
        if (endBalances.size() >= 2 && meanIncome > 0) {
            trend = (endBalances.getLast() - endBalances.getFirst()) / (endBalances.size() - 1) / meanIncome;
        }
        double minBalRatio = meanIncome > 0 && minBalance != Double.POSITIVE_INFINITY ? minBalance / meanIncome : 0.0;
        Double inflation = statedAnnualIncome != null && meanIncome > 0 ? statedAnnualIncome / (12.0 * meanIncome) : null;

        return new CashflowFeatures(
                monthsWithIncome,
                round(meanIncome, 2),
                round(clamp(cv, 0.02, 1.2), 4),
                round(clamp(trend, -0.5, 0.5), 4),
                round(clamp(minBalRatio, -0.3, 3.0), 4),
                nsf,
                regularity(months.values(), m -> m.rentPaid),
                regularity(months.values(), m -> m.utilityPaid),
                regularity(months.values(), m -> m.telcoPaid),
                round(meanIncome > 0 ? clamp(obligations / meanIncome, 0, 1.2) : 1.2, 4),
                round(totalDebits > 0 ? discretionary / totalDebits : 0.0, 4),
                gambling,
                inflation == null ? null : round(inflation, 4),
                spendByCategory);
    }

    /** Share of observed months in which the bill was paid; null when the bill never appears at all. */
    private static Double regularity(Iterable<MonthAgg> months, java.util.function.Predicate<MonthAgg> paid) {
        int total = 0, hits = 0;
        for (MonthAgg m : months) {
            total++;
            if (paid.test(m)) {
                hits++;
            }
        }
        return hits == 0 ? null : round((double) hits / total, 4);
    }

    private static final class MonthAgg {
        double income, debits, obligations, discretionary;
        boolean rentPaid, utilityPaid, telcoPaid;
        Double lastBalance;
    }

    private static double mean(double[] xs) {
        return xs.length == 0 ? 0 : java.util.Arrays.stream(xs).average().orElse(0);
    }

    private static double std(double[] xs) {
        double m = mean(xs);
        return Math.sqrt(java.util.Arrays.stream(xs).map(x -> (x - m) * (x - m)).average().orElse(0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v, int places) {
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }
}
