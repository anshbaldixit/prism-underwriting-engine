package dev.anshdixit.prism.model;

import dev.anshdixit.prism.fraud.FraudRules;
import dev.anshdixit.prism.scoring.Scorecard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the versioned model artefacts produced by the ML pipeline. They are packaged with the service so a
 * deployment is reproducible: the exact bins, coefficients and rules that scored a decision are the ones on
 * the classpath, recorded by version on every decision row.
 */
@Configuration
public class ModelArtifactsConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelArtifactsConfig.class);

    /**
     * Model files are written by Python in snake_case. This mapper is deliberately NOT a bean: exposing a second
     * JsonMapper would make Spring Boot's auto-configured (camelCase) API mapper back off.
     */
    private static final JsonMapper MODEL_JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static JsonMapper modelJson() {
        return MODEL_JSON;
    }

    @Bean
    public Scorecard scorecard() {
        Scorecard sc = read(MODEL_JSON, "model/scorecard.json", Scorecard.class);
        log.info("Loaded scorecard {} v{} ({} features, trained {})", sc.modelId(), sc.version(), sc.features().size(), sc.trainedAt());
        return sc;
    }

    @Bean
    public FraudRules fraudRules() {
        FraudRules rules = read(MODEL_JSON, "model/fraud_rules.json", FraudRules.class);
        log.info("Loaded fraud rules v{} ({} rules)", rules.version(), rules.rules().size());
        return rules;
    }

    @Bean
    public ModelCard modelCard() {
        JsonNode node = read(MODEL_JSON, "model/model_card.json", JsonNode.class);
        return new ModelCard(node);
    }

    static <T> T read(JsonMapper mapper, String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load model artefact " + path, e);
        }
    }

    /** Thin wrapper so the raw model card JSON can be exposed read-only through the API. */
    public record ModelCard(JsonNode json) {
    }
}
