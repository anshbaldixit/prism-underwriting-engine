package dev.anshdixit.prism.cashflow;

import dev.anshdixit.prism.ai.EmbeddingClient;
import dev.anshdixit.prism.ai.VectorFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic categorisation: embed the raw description, find the nearest exemplar phrase with pgvector
 * (cosine distance, HNSW index), take its category. Exemplars are (re)seeded at start-up whenever the
 * embedding provider changes, because vectors from different models are not comparable. Seeding also runs lazily on
 * first use, so a request that arrives before the start-up runner has finished never categorises against an empty store.
 */
@Component
@Order(1)
public class PgVectorTransactionCategorizer implements TransactionCategorizer, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgVectorTransactionCategorizer.class);

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final TransactionTemplate tx;
    private final Map<String, Categorization> cache = new ConcurrentHashMap<>();
    private volatile boolean seeded;

    public PgVectorTransactionCategorizer(JdbcClient jdbc, EmbeddingClient embeddings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfNeeded();
    }

    public synchronized void seedIfNeeded() {
        if (seeded) {
            return;
        }
        long existing = jdbc.sql("select count(*) from category_exemplars where provider = :p")
                .param("p", embeddings.providerName()).query(Long.class).single();
        if (existing > 0) {
            seeded = true;
            return;
        }
        tx.executeWithoutResult(status -> {
            jdbc.sql("delete from category_exemplars").update();
            int n = 0;
            for (TransactionCategory cat : TransactionCategory.values()) {
                for (String phrase : cat.exemplars()) {
                    jdbc.sql("insert into category_exemplars (id, category, text, provider, embedding) values (:id, :cat, :text, :p, cast(:emb as vector))")
                            .param("id", UUID.randomUUID()).param("cat", cat.name()).param("text", phrase)
                            .param("p", embeddings.providerName()).param("emb", VectorFormat.toLiteral(embeddings.embed(phrase)))
                            .update();
                    n++;
                }
            }
            log.info("Seeded {} category exemplars with {} embeddings", n, embeddings.providerName());
        });
        seeded = true;
    }

    @Override
    public Map<String, Categorization> categorize(Collection<String> descriptions) {
        seedIfNeeded();
        Map<String, Categorization> out = new HashMap<>();
        for (String d : descriptions) {
            out.put(d, cache.computeIfAbsent(d, this::nearest));
        }
        return out;
    }

    private Categorization nearest(String description) {
        String literal = VectorFormat.toLiteral(embeddings.embed(description));
        return jdbc.sql("select category, text, 1 - (embedding <=> cast(:q as vector)) as similarity " +
                        "from category_exemplars order by embedding <=> cast(:q as vector) limit 1")
                .param("q", literal)
                .query((rs, i) -> new Categorization(TransactionCategory.valueOf(rs.getString("category")),
                        Math.max(0, Math.min(1, rs.getDouble("similarity"))), rs.getString("text")))
                .optional()
                .orElse(new Categorization(TransactionCategory.OTHER, 0, null));
    }
}
