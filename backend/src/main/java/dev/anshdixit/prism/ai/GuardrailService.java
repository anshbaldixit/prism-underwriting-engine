package dev.anshdixit.prism.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The single path through which any LLM is called. Order of operations:
 * <ol>
 *   <li>PII redaction of the rendered prompts (defence in depth).</li>
 *   <li>Managed input guardrail (Bedrock ApplyGuardrail) when configured.</li>
 *   <li>Model call with a hard timeout.</li>
 *   <li>Managed output guardrail when configured.</li>
 *   <li>Local schema + model-consistency validation; one corrective retry; then deterministic fallback.</li>
 *   <li>Audit row persisted regardless of outcome.</li>
 * </ol>
 * The contract to callers is simple: you always get a validated JSON document of the task's schema.
 */
@Service
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);

    private final LlmClient primary;
    private final LlmClient fallback;
    private final TextGuardrail managedGuardrail;
    private final LlmOutputValidator validator;
    private final PiiRedactor redactor;
    private final AiInvocationRepository audit;
    private final int timeoutSeconds;

    public GuardrailService(LlmClient primary, LlmClient fallback, TextGuardrail managedGuardrail, LlmOutputValidator validator,
                            PiiRedactor redactor, AiInvocationRepository audit, int timeoutSeconds) {
        this.primary = primary;
        this.fallback = fallback;
        this.managedGuardrail = managedGuardrail;
        this.validator = validator;
        this.redactor = redactor;
        this.audit = audit;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record Guarded(JsonNode json, String provider, String model, boolean validated, boolean fallbackUsed, String guardrailAction, long latencyMs) {
    }

    public Guarded generate(LlmRequest request, UUID applicationId) {
        LlmRequest clean = new LlmRequest(request.task(), redactor.redact(request.systemPrompt()), redactor.redact(request.userPrompt()),
                request.context(), request.maxTokens());
        AiInvocation row = new AiInvocation(clean.task().name(), primary.providerName(), null, applicationId, sha256(clean.systemPrompt() + "\n" + clean.userPrompt()));
        long t0 = System.nanoTime();
        String guardrailAction = "NONE";

        try {
            TextGuardrail.Outcome in = managedGuardrail.checkInput(clean.userPrompt());
            if (in.action() == TextGuardrail.Action.INTERVENED) {
                guardrailAction = "INPUT_BLOCKED";
                return finish(row, fallback(clean), t0, guardrailAction, true, "managed guardrail blocked the input");
            }
            if (in.action() == TextGuardrail.Action.UNAVAILABLE) {
                guardrailAction = "UNAVAILABLE";
            }

            LlmResponse resp = callWithTimeout(primary, clean);
            row.setModel(resp.model());
            row.setInputTokens(resp.inputTokens());
            row.setOutputTokens(resp.outputTokens());

            TextGuardrail.Outcome out = managedGuardrail.checkOutput(resp.text());
            if (out.action() == TextGuardrail.Action.INTERVENED) {
                guardrailAction = "OUTPUT_BLOCKED";
                return finish(row, fallback(clean), t0, guardrailAction, true, "managed guardrail blocked the output");
            }

            LlmOutputValidator.Result result = validator.validate(clean.task(), resp.text(), clean.context());
            if (!result.valid() && !primary.isOffline()) {
                log.warn("LLM output failed validation for {} ({}); retrying once", clean.task(), result.errors());
                LlmRequest corrective = new LlmRequest(clean.task(), clean.systemPrompt(),
                        clean.userPrompt() + "\n\nYour previous answer was rejected for these reasons: " + result.errors()
                                + "\nReturn a corrected JSON object only.", clean.context(), clean.maxTokens());
                resp = callWithTimeout(primary, corrective);
                result = validator.validate(clean.task(), resp.text(), clean.context());
            }
            if (!result.valid()) {
                return finish(row, fallback(clean), t0, guardrailAction, true, String.join("; ", result.errors()));
            }
            row.setOutputValid(true);
            row.setProvider(resp.provider());
            return finish(row, new Guarded(result.json(), resp.provider(), resp.model(), true, false, guardrailAction, 0), t0, guardrailAction, false, null);

        } catch (Exception e) {
            log.warn("LLM provider {} failed for {}: {} - using deterministic fallback", primary.providerName(), clean.task(), e.toString());
            return finish(row, fallback(clean), t0, guardrailAction, true, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Guarded fallback(LlmRequest request) {
        LlmResponse resp = fallback.complete(request);
        LlmOutputValidator.Result result = validator.validate(request.task(), resp.text(), request.context());
        if (!result.valid()) {
            // The template engine is built from the same context the validator checks; this indicates a bug, not bad luck.
            throw new IllegalStateException("Fallback output failed validation: " + result.errors());
        }
        return new Guarded(result.json(), resp.provider(), resp.model(), true, true, "NONE", 0);
    }

    private Guarded finish(AiInvocation row, Guarded g, long t0, String guardrailAction, boolean fallbackUsed, String errors) {
        long latency = (System.nanoTime() - t0) / 1_000_000;
        row.setLatencyMs(latency);
        row.setGuardrailAction(guardrailAction);
        row.setFallbackUsed(fallbackUsed);
        row.setOutputValid(g.validated());
        row.setValidationErrors(errors);
        if (fallbackUsed) {
            row.setProvider(g.provider());
            row.setModel(g.model());
        }
        try {
            audit.save(row);
        } catch (RuntimeException e) {
            log.error("Could not persist AI audit row", e);
        }
        return new Guarded(g.json(), g.provider(), g.model(), g.validated(), g.fallbackUsed(), guardrailAction, latency);
    }

    private LlmResponse callWithTimeout(LlmClient client, LlmRequest request) throws Exception {
        if (client.isOffline()) {
            return client.complete(request);
        }
        CompletableFuture<LlmResponse> f = CompletableFuture.supplyAsync(() -> client.complete(request));
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimeoutException("LLM call exceeded " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> describe() {
        return Map.of("primaryProvider", primary.providerName(), "fallbackProvider", fallback.providerName(),
                "managedGuardrail", managedGuardrail != TextGuardrail.NONE, "timeoutSeconds", timeoutSeconds);
    }
}
