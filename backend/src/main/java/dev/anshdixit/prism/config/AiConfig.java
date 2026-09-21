package dev.anshdixit.prism.config;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.anshdixit.prism.ai.AiInvocationRepository;
import dev.anshdixit.prism.ai.EmbeddingClient;
import dev.anshdixit.prism.ai.GuardrailService;
import dev.anshdixit.prism.ai.LlmClient;
import dev.anshdixit.prism.ai.LlmOutputValidator;
import dev.anshdixit.prism.ai.PiiRedactor;
import dev.anshdixit.prism.ai.TextGuardrail;
import dev.anshdixit.prism.ai.anthropic.AnthropicLlmClient;
import dev.anshdixit.prism.ai.bedrock.BedrockConverseLlmClient;
import dev.anshdixit.prism.ai.bedrock.BedrockGuardrail;
import dev.anshdixit.prism.ai.bedrock.TitanEmbeddingClient;
import dev.anshdixit.prism.ai.offline.HashingEmbeddingClient;
import dev.anshdixit.prism.ai.offline.OfflineTemplateLlmClient;
import dev.anshdixit.prism.ai.openai.OpenAiCompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Provider selection is pure configuration ({@code PRISM_AI_PROVIDER}, {@code PRISM_EMBEDDING_PROVIDER}).
 * No adapter holds a credential: Bedrock uses the AWS default chain, Anthropic reads ANTHROPIC_API_KEY,
 * and the offline adapters need nothing at all.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    @ConditionalOnExpression("'${prism.ai.provider}' == 'bedrock' or '${prism.ai.embedding-provider}' == 'bedrock'")
    public BedrockRuntimeClient bedrockRuntimeClient(PrismProperties props) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(props.ai().bedrock().region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(props.ai().timeoutSeconds()))
                        .build())
                .build();
    }

    @Bean
    public LlmClient fallbackLlmClient(JsonMapper apiJsonMapper) {
        return new OfflineTemplateLlmClient(apiJsonMapper);
    }

    @Bean
    @Primary
    public LlmClient primaryLlmClient(PrismProperties props, ObjectProvider<BedrockRuntimeClient> bedrock, @Qualifier("fallbackLlmClient") LlmClient fallbackLlmClient, JsonMapper apiJsonMapper) {
        String provider = props.ai().provider();
        LlmClient client = switch (provider) {
            case "bedrock" -> new BedrockConverseLlmClient(bedrock.getObject(), props.ai().bedrock().modelId());
            case "anthropic" -> new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv(), props.ai().anthropic().model());
            case "openai-compatible" -> openAiCompatible(props, apiJsonMapper);
            default -> fallbackLlmClient;
        };
        log.info("AI provider: {} (model {})", client.providerName(), client.modelName() == null ? "template" : client.modelName());
        return client;
    }

    private static LlmClient openAiCompatible(PrismProperties props, JsonMapper mapper) {
        PrismProperties.OpenAiCompatible cfg = props.ai().openaiCompatible();
        String key = cfg.apiKeyEnv() == null || cfg.apiKeyEnv().isBlank() ? null : System.getenv(cfg.apiKeyEnv());
        if (cfg.apiKeyEnv() != null && !cfg.apiKeyEnv().isBlank() && (key == null || key.isBlank())) {
            throw new IllegalStateException("PRISM_AI_PROVIDER=openai-compatible but environment variable " + cfg.apiKeyEnv() + " is not set");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new OpenAiCompatibleLlmClient(http, mapper, cfg.baseUrl(), cfg.model(), key, cfg.reasoningEffort(), Duration.ofSeconds(props.ai().timeoutSeconds()));
    }

    @Bean
    public EmbeddingClient embeddingClient(PrismProperties props, ObjectProvider<BedrockRuntimeClient> bedrock, JsonMapper apiJsonMapper) {
        EmbeddingClient client = "bedrock".equals(props.ai().embeddingProvider())
                ? new TitanEmbeddingClient(bedrock.getObject(), props.ai().bedrock().embeddingModelId(), apiJsonMapper)
                : new HashingEmbeddingClient();
        log.info("Embedding provider: {}", client.providerName());
        return client;
    }

    @Bean
    public TextGuardrail managedGuardrail(PrismProperties props, ObjectProvider<BedrockRuntimeClient> bedrock) {
        if (props.ai().bedrock().guardrailConfigured() && bedrock.getIfAvailable() != null) {
            log.info("Bedrock managed guardrail enabled: {} v{}", props.ai().bedrock().guardrailId(), props.ai().bedrock().guardrailVersion());
            return new BedrockGuardrail(bedrock.getObject(), props.ai().bedrock().guardrailId(), props.ai().bedrock().guardrailVersion());
        }
        return TextGuardrail.NONE;
    }

    @Bean
    public GuardrailService guardrailService(@Qualifier("primaryLlmClient") LlmClient primaryLlmClient, @Qualifier("fallbackLlmClient") LlmClient fallbackLlmClient, TextGuardrail managedGuardrail,
                                             LlmOutputValidator validator, PiiRedactor redactor, AiInvocationRepository audit, PrismProperties props) {
        return new GuardrailService(primaryLlmClient, fallbackLlmClient, managedGuardrail, validator, redactor, audit, props.ai().timeoutSeconds());
    }
}
