package dev.anshdixit.prism.fraud;

import java.util.List;
import java.util.Map;

/** Rule set deserialised from {@code model/fraud_rules.json}; the Python evaluation and this engine share it. */
public record FraudRules(String version, Thresholds thresholds, List<Rule> rules, Map<String, String> outcomes) {

    public record Thresholds(int stepUp, int block) {
    }

    public record Rule(String id, String name, String feature, String op, double value, int points, String signal) {

        /** A rule never fires on an unobserved signal - absence of evidence is not evidence of fraud. */
        public boolean fires(Double x) {
            if (x == null || x.isNaN()) {
                return false;
            }
            return switch (op) {
                case ">" -> x > value;
                case ">=" -> x >= value;
                case "<" -> x < value;
                case "<=" -> x <= value;
                case "==" -> x == value;
                default -> throw new IllegalStateException("Unknown operator in fraud rule " + id + ": " + op);
            };
        }
    }
}
