package dev.anshdixit.prism.ai;

import java.util.Map;

/**
 * A fully rendered prompt plus the structured facts it was rendered from. The structured context is what the
 * offline adapter and the output validator work from - the model never sees anything the validator cannot check.
 */
public record LlmRequest(LlmTask task, String systemPrompt, String userPrompt, Map<String, Object> context, int maxTokens) {
}
