package dev.anshdixit.prism.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Typed, validated configuration. Every secret arrives via environment variable (see application.yml);
 * nothing sensitive has a default. Validation runs at startup so a mis-configured deployment fails fast.
 */
@Validated
@ConfigurationProperties(prefix = "prism")
public record PrismProperties(Security security, Ai ai) {

    public record Security(
            @NotBlank @Size(min = 32, message = "PRISM_JWT_SECRET must be at least 32 characters") String jwtSecret,
            @Min(5) int tokenTtlMinutes,
            String seedPassword,
            List<String> corsOrigins) {
    }

    public record Ai(
            @Pattern(regexp = "bedrock|anthropic|openai-compatible|offline") String provider,
            @Pattern(regexp = "bedrock|offline") String embeddingProvider,
            @Min(200) int maxOutputTokens,
            @Min(5) int timeoutSeconds,
            Bedrock bedrock,
            Anthropic anthropic,
            OpenAiCompatible openaiCompatible) {
    }

    /** Any OpenAI-protocol endpoint: Groq, a local Ollama/vLLM, or a model server inside a VPC. The key is read from
     *  the environment variable named by {@code apiKeyEnv} (empty for local servers that need none). */
    public record OpenAiCompatible(String baseUrl, String model, String apiKeyEnv, String reasoningEffort) {
    }

    public record Bedrock(String region, String modelId, String embeddingModelId, String guardrailId, String guardrailVersion) {
        public boolean guardrailConfigured() {
            return guardrailId != null && !guardrailId.isBlank();
        }
    }

    public record Anthropic(String model) {
    }
}
