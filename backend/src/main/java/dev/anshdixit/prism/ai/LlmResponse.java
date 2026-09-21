package dev.anshdixit.prism.ai;

public record LlmResponse(String text, String provider, String model, Integer inputTokens, Integer outputTokens, long latencyMs) {
}
