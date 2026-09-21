package dev.anshdixit.prism.ai;

/** Single-turn completion against whichever provider is configured (Bedrock, Anthropic API, or offline templates). */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    String providerName();

    /** Model identifier for audit rows and UI badges; null for providers without a meaningful model id. */
    default String modelName() {
        return null;
    }

    /** True for the deterministic template engine; used to skip retries that cannot change anything. */
    default boolean isOffline() {
        return false;
    }
}
