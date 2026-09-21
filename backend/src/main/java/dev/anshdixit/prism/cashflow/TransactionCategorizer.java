package dev.anshdixit.prism.cashflow;

import java.util.Collection;
import java.util.Map;

/** Maps raw transaction descriptions to categories. Implemented with pgvector k-NN; faked in unit tests. */
public interface TransactionCategorizer {

    record Categorization(TransactionCategory category, double confidence, String matchedExemplar) {
    }

    Map<String, Categorization> categorize(Collection<String> descriptions);
}
