package dev.anshdixit.prism.ai;

/** The closed set of things the LLM is allowed to do in this system. Each task has a prompt template and a validator. */
public enum LlmTask {
    ADVERSE_ACTION_NOTICE,
    UNDERWRITER_SUMMARY,
    COPILOT_ANSWER
}
