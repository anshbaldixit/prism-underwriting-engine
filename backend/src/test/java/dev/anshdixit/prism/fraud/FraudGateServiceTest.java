package dev.anshdixit.prism.fraud;

import dev.anshdixit.prism.model.ModelArtifactsConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudGateServiceTest {

    static FraudGateService gate;

    @BeforeAll
    static void setup() {
        gate = new FraudGateService(new ModelArtifactsConfig().fraudRules());
    }

    @Test
    void cleanApplicantPasses() {
        FraudResult r = gate.evaluate(Map.of("income_inflation_ratio", 1.02, "paste_ssn", 0.0, "device_apps_30d", 0.0,
                "email_age_days", 1500.0, "voip_phone", 0.0, "session_seconds", 420.0, "income_field_edits", 1.0, "bank_linked", 1.0));
        assertThat(r.outcome()).isEqualTo(FraudResult.Outcome.PASS);
        assertThat(r.points()).isZero();
    }

    @Test
    void syntheticIdentitySignatureBlocks() {
        FraudResult r = gate.evaluate(Map.of("paste_ssn", 1.0, "email_age_days", 12.0, "voip_phone", 1.0, "session_seconds", 84.0,
                "device_apps_30d", 4.0, "bank_linked", 1.0, "income_inflation_ratio", 1.0));
        assertThat(r.outcome()).isEqualTo(FraudResult.Outcome.BLOCK);
        assertThat(r.firedRules()).extracting(FraudResult.FiredRule::id).contains("F02", "F03", "F04", "F05", "F06");
        assertThat(r.verificationItems()).anyMatch(s -> s.contains("liveness"));
    }

    @Test
    void incomeInflationAloneIsStepUpNotBlock() {
        FraudResult r = gate.evaluate(Map.of("income_inflation_ratio", 2.0, "bank_linked", 1.0, "session_seconds", 400.0));
        assertThat(r.outcome()).isEqualTo(FraudResult.Outcome.STEP_UP);
        assertThat(r.points()).isEqualTo(40);
    }

    @Test
    void unobservedSignalsNeverFire() {
        Map<String, Double> signals = new HashMap<>();
        signals.put("income_inflation_ratio", null);
        signals.put("email_age_days", null);
        FraudResult r = gate.evaluate(signals);
        assertThat(r.points()).isZero();
        assertThat(r.outcome()).isEqualTo(FraudResult.Outcome.PASS);
    }

    @Test
    void singleWeakSignalNeverBlocks() {
        for (FraudRules.Rule rule : new ModelArtifactsConfig().fraudRules().rules()) {
            assertThat(rule.points()).isLessThan(60);
        }
    }
}
