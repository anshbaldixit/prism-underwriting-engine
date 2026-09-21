package dev.anshdixit.prism.ai;

/**
 * Optional managed content guardrail (Amazon Bedrock Guardrails via ApplyGuardrail). Runs independently of
 * which model answered, so the same PII-masking / denied-topic policy applies to every provider.
 */
public interface TextGuardrail {

    enum Action { NONE, INTERVENED, UNAVAILABLE }

    record Outcome(Action action, String text) {
        public static Outcome pass(String text) {
            return new Outcome(Action.NONE, text);
        }
    }

    Outcome checkInput(String text);

    Outcome checkOutput(String text);

    /** No-op used when no managed guardrail is configured; the local validator still runs. */
    TextGuardrail NONE = new TextGuardrail() {
        @Override
        public Outcome checkInput(String text) {
            return Outcome.pass(text);
        }

        @Override
        public Outcome checkOutput(String text) {
            return Outcome.pass(text);
        }
    };
}
