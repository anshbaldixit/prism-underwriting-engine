package dev.anshdixit.prism.ai;

/** Single-turn completion against whichever provider is configured (Bedrock, Anthropic API, or offline templates). */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    String providerName();

    /** True for the deterministic template engine; used to skip retries that cannot change anything. */
    default boolean isOffline() {
        return false;
    }
}
