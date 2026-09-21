package dev.anshdixit.prism.similar;

import dev.anshdixit.prism.ai.EmbeddingClient;
import dev.anshdixit.prism.ai.VectorFormat;
import dev.anshdixit.prism.model.ModelArtifactsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Applicants like this one": nearest neighbours among historical decisions with known 12-month outcomes.
 * Strictly advisory context for the underwriter and the copilot - it is never an input to the score.
 * Seeded from the ML hold-out sample so the outcomes are real (synthetic) outcomes, not labels invented here.
 */
@Service
public class SimilarApplicantService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimilarApplicantService.class);
    private static final int SEED_ROWS = 800;

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final ProfileTextBuilder profiles;

    public SimilarApplicantService(JdbcClient jdbc, EmbeddingClient embeddings, ProfileTextBuilder profiles) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.profiles = profiles;
    }

    public record Neighbour(String id, String fileType, int score, double pd, String decision, boolean defaulted, List<String> reasonCodes,
                            String profileText, double similarity) {
    }

    public record Cohort(int size, double defaultRate, double approvalRate, List<Neighbour> top) {
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = jdbc.sql("select count(*) from historical_applicants where provider = :p").param("p", embeddings.providerName()).query(Long.class).single();
        if (existing > 0) {
            return;
        }
        jdbc.sql("delete from historical_applicants").update();
        JsonNode rows;
        try (InputStream in = new ClassPathResource("model/historical_sample.json").getInputStream()) {
            rows = ModelArtifactsConfig.modelJson().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int n = 0;
        for (JsonNode r : rows) {
            if (n >= SEED_ROWS) {
                break;
            }
            Map<String, Double> f = new HashMap<>();
            r.get("features").properties().forEach(e -> f.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asDouble()));
            String text = profiles.build(r.get("file_type").asString(), r.get("bank_linked").asInt() == 1, f,
                    f.get("requested_amount"), f.get("stated_annual_income"));
            jdbc.sql("insert into historical_applicants (id, file_type, bank_linked, score, pd, decision, defaulted_12m, reason_codes, features, profile_text, provider, embedding) " +
                            "values (:id, :ft, :bl, :score, :pd, :dec, :def, :rc, :feat, :txt, :p, cast(:emb as vector))")
                    .param("id", r.get("applicant_id").asString()).param("ft", r.get("file_type").asString())
                    .param("bl", r.get("bank_linked").asInt() == 1).param("score", r.get("score").asInt()).param("pd", r.get("pd").asDouble())
                    .param("dec", r.get("decision").asString()).param("def", r.get("defaulted_12m").asInt() == 1)
                    .param("rc", r.get("reason_codes").toString()).param("feat", r.get("features").toString()).param("txt", text)
                    .param("p", embeddings.providerName()).param("emb", VectorFormat.toLiteral(embeddings.embed(text)))
                    .update();
            n++;
        }
        log.info("Seeded {} historical applicants with {} embeddings", n, embeddings.providerName());
    }

    /** Stores the live application's profile so it can itself be found later (and for audit). */
    public void storeProfile(UUID applicationId, String profileText) {
        jdbc.sql("update applications set profile_text = :t, profile_embedding = cast(:emb as vector) where id = :id")
                .param("t", profileText).param("emb", VectorFormat.toLiteral(embeddings.embed(profileText))).param("id", applicationId)
                .update();
    }

    public Cohort findSimilar(String profileText, int k) {
        String literal = VectorFormat.toLiteral(embeddings.embed(profileText));
        List<Neighbour> all = jdbc.sql("select id, file_type, score, pd, decision, defaulted_12m, reason_codes, profile_text, " +
                        "1 - (embedding <=> cast(:q as vector)) as similarity from historical_applicants " +
                        "order by embedding <=> cast(:q as vector) limit :k")
                .param("q", literal).param("k", Math.max(k, 25))
                .query((rs, i) -> new Neighbour(rs.getString("id"), rs.getString("file_type"), rs.getInt("score"), rs.getDouble("pd"),
                        rs.getString("decision"), rs.getBoolean("defaulted_12m"), parseCodes(rs.getString("reason_codes")),
                        rs.getString("profile_text"), rs.getDouble("similarity")))
                .list();
        double defaultRate = all.stream().mapToDouble(nb -> nb.defaulted() ? 1 : 0).average().orElse(0);
        double approvalRate = all.stream().mapToDouble(nb -> "APPROVE".equals(nb.decision()) ? 1 : 0).average().orElse(0);
        return new Cohort(all.size(), defaultRate, approvalRate, all.subList(0, Math.min(k, all.size())));
    }

    private static List<String> parseCodes(String json) {
        JsonNode n = ModelArtifactsConfig.modelJson().readTree(json);
        return n.valueStream().map(JsonNode::asString).toList();
    }
}
