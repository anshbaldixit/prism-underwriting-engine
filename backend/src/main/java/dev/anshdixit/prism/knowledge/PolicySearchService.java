package dev.anshdixit.prism.knowledge;

import dev.anshdixit.prism.ai.EmbeddingClient;
import dev.anshdixit.prism.ai.VectorFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Retrieval over internal policy documents ({@code resources/policy/*.md}), chunked by section heading and
 * embedded into pgvector. The copilot cites (doc, section) pairs from here and nothing else.
 */
@Service
@Order(2)
public class PolicySearchService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchService.class);

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final TransactionTemplate tx;
    private volatile boolean seeded;

    public PolicySearchService(JdbcClient jdbc, EmbeddingClient embeddings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.tx = tx;
    }

    public record Chunk(String doc, String section, String content, double similarity) {
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfNeeded();
    }

    public synchronized void seedIfNeeded() {
        if (seeded) {
            return;
        }
        long existing = jdbc.sql("select count(*) from policy_chunks where provider = :p").param("p", embeddings.providerName()).query(Long.class).single();
        if (existing > 0) {
            seeded = true;
            return;
        }
        tx.executeWithoutResult(status -> {
            jdbc.sql("delete from policy_chunks").update();
            int n = 0;
            for (Chunk c : loadChunks()) {
                jdbc.sql("insert into policy_chunks (id, doc, section, content, provider, embedding) values (:id, :doc, :sec, :content, :p, cast(:emb as vector))")
                        .param("id", UUID.randomUUID()).param("doc", c.doc()).param("sec", c.section()).param("content", c.content())
                        .param("p", embeddings.providerName()).param("emb", VectorFormat.toLiteral(embeddings.embed(c.section() + ". " + c.content())))
                        .update();
                n++;
            }
            log.info("Seeded {} policy chunks with {} embeddings", n, embeddings.providerName());
        });
        seeded = true;
    }

    public List<Chunk> search(String query, int k) {
        seedIfNeeded();
        String literal = VectorFormat.toLiteral(embeddings.embed(query));
        return jdbc.sql("select doc, section, content, 1 - (embedding <=> cast(:q as vector)) as similarity from policy_chunks " +
                        "order by embedding <=> cast(:q as vector) limit :k")
                .param("q", literal).param("k", k)
                .query((rs, i) -> new Chunk(rs.getString("doc"), rs.getString("section"), rs.getString("content"), rs.getDouble("similarity")))
                .list();
    }

    /** Splits each markdown file on "## " headings; the H1 title becomes the document name. */
    static List<Chunk> loadChunks() {
        List<Chunk> chunks = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:policy/*.md");
            for (Resource r : files) {
                String text = r.getContentAsString(StandardCharsets.UTF_8);
                String doc = r.getFilename().replace(".md", "");
                String section = null;
                StringBuilder body = new StringBuilder();
                for (String line : text.split("\\R")) {
                    if (line.startsWith("## ")) {
                        if (section != null) {
                            chunks.add(new Chunk(doc, section, body.toString().trim(), 1.0));
                        }
                        section = line.substring(3).trim();
                        body.setLength(0);
                    } else if (!line.startsWith("# ")) {
                        body.append(line).append('\n');
                    }
                }
                if (section != null) {
                    chunks.add(new Chunk(doc, section, body.toString().trim(), 1.0));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return chunks;
    }
}
