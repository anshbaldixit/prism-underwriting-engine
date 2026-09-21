package dev.anshdixit.prism.ai.bedrock;

import dev.anshdixit.prism.ai.TextGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailOutputContent;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTextBlock;

/**
 * Amazon Bedrock Guardrails applied out-of-band with the ApplyGuardrail API, so the same managed policy
 * (PII anonymisation, denied topics, harmful-content filters, grounding) protects every provider path.
 * Fails open to the local validator - a guardrail outage must not stop compliant notices being issued.
 */
public class BedrockGuardrail implements TextGuardrail {

    private static final Logger log = LoggerFactory.getLogger(BedrockGuardrail.class);

    private final BedrockRuntimeClient client;
    private final String guardrailId;
    private final String guardrailVersion;

    public BedrockGuardrail(BedrockRuntimeClient client, String guardrailId, String guardrailVersion) {
        this.client = client;
        this.guardrailId = guardrailId;
        this.guardrailVersion = guardrailVersion;
    }

    @Override
    public Outcome checkInput(String text) {
        return apply(text, GuardrailContentSource.INPUT);
    }

    @Override
    public Outcome checkOutput(String text) {
        return apply(text, GuardrailContentSource.OUTPUT);
    }

    private Outcome apply(String text, GuardrailContentSource source) {
        try {
            ApplyGuardrailResponse resp = client.applyGuardrail(ApplyGuardrailRequest.builder()
                    .guardrailIdentifier(guardrailId)
                    .guardrailVersion(guardrailVersion)
                    .source(source)
                    .content(GuardrailContentBlock.builder().text(GuardrailTextBlock.builder().text(text).build()).build())
                    .build());
            if (resp.action() == GuardrailAction.GUARDRAIL_INTERVENED) {
                String masked = resp.outputs().stream().map(GuardrailOutputContent::text).reduce("", String::concat);
                return new Outcome(Action.INTERVENED, masked);
            }
            return Outcome.pass(text);
        } catch (RuntimeException e) {
            log.warn("Bedrock guardrail unavailable ({}); continuing with local validation only", e.toString());
            return new Outcome(Action.UNAVAILABLE, text);
        }
    }
}
