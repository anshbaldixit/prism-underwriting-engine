package dev.anshdixit.prism.application;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack flow against a real pgvector database: login -> submit persona -> decision -> underwriter summary
 * -> copilot. Runs only when Docker is available; the unit tests cover the engine without it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "prism.security.jwt-secret=integration-test-secret-0123456789-abcdefghijklmnop",
        "prism.security.seed-password=it-password",
        "prism.ai.provider=offline",
        "prism.ai.embedding-provider=offline"})
@AutoConfigureTestRestTemplate
class ApplicationFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    static JsonNode personas;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void loadPersonas() throws Exception {
        try (InputStream in = new ClassPathResource("demo/personas.json").getInputStream()) {
            personas = JSON.readTree(in);
        }
    }

    private String login(String user) {
        ResponseEntity<JsonNode> r = rest.postForEntity("/api/auth/login", Map.of("username", user, "password", "it-password"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("token").asString();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static ObjectNode requestFor(String personaId) {
        JsonNode p = null;
        for (JsonNode n : personas) {
            if (n.get("id").asString().equals(personaId)) {
                p = n;
            }
        }
        assertThat(p).isNotNull();
        ObjectNode req = JSON.createObjectNode();
        ObjectNode applicant = req.putObject("applicant");
        applicant.put("fullName", p.get("form").get("fullName").asString());
        applicant.put("email", p.get("form").get("email").asString());
        applicant.put("phone", p.get("form").get("phone").asString());
        applicant.put("nationalId", "123-45-6789");
        applicant.put("employmentType", p.get("form").get("employmentType").asString());
        applicant.put("statedAnnualIncome", p.get("form").get("statedAnnualIncome").asDouble());
        req.putObject("loan").put("requestedAmount", p.get("form").get("requestedAmount").asDouble()).put("purpose", p.get("form").get("loanPurpose").asString());
        req.putObject("consent").put("bankData", true).put("altData", true);
        req.set("bureau", p.get("bureau"));
        req.set("behaviour", p.get("behaviour"));
        var txns = req.putArray("transactions");
        for (JsonNode t : p.get("transactions")) {
            txns.addObject().put("date", t.get("date").asString()).put("description", t.get("description").asString())
                    .put("amount", t.get("amount").asDouble()).put("balanceAfter", t.get("balance_after").asDouble());
        }
        req.put("personaId", personaId);
        return req;
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        ResponseEntity<String> r = rest.getForEntity("/api/applications", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void newToCreditGigWorkerIsApprovedOnCashFlowEvidence() {
        String token = login("applicant");
        ResponseEntity<JsonNode> r = rest.exchange("/api/applications", HttpMethod.POST, new HttpEntity<>(requestFor("ntc-gig-worker"), bearer(token)), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode d = r.getBody().get("decision");
        assertThat(d.get("decision").asString()).isEqualTo("APPROVE");
        assertThat(d.get("creditLimit").asDouble()).isGreaterThan(0);
        assertThat(d.get("notice").get("decision").asString()).isEqualTo("APPROVE");
        assertThat(d.get("noticeValidated").asBoolean()).isTrue();
        assertThat(r.getBody().get("cashflow").get("features").get("monthlyIncome").asDouble()).isGreaterThan(3000);
        assertThat(r.getBody().get("cashflow").get("features").get("rentOntimeRatio").asDouble()).isEqualTo(1.0);
        assertThat(r.getBody().get("applicant").get("nationalIdOnFile").asBoolean()).isTrue();
        // Underwriter material is filtered out of the applicant's own view - by the server, not the UI.
        assertThat(d.get("fraud").isNull()).isTrue();
        assertThat(d.get("basis").isNull()).isTrue();
        assertThat(r.getBody().get("similar").isNull()).isTrue();
        assertThat(r.getBody().get("behaviour").isNull()).isTrue();

        String id = r.getBody().get("id").asString();
        ResponseEntity<JsonNode> u = rest.exchange("/api/applications/" + id, HttpMethod.GET, new HttpEntity<>(bearer(login("underwriter"))), JsonNode.class);
        assertThat(u.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(u.getBody().get("decision").get("fraud").get("outcome").asString()).isEqualTo("PASS");
        assertThat(u.getBody().get("decision").get("basis").asString()).isEqualTo("CREDIT_POLICY");
        assertThat(u.getBody().get("similar").get("size").asInt()).isGreaterThan(0);
    }

    @Test
    void syntheticIdentityIsBlockedBeforePricingAndUnderwriterToolsWork() {
        String applicant = login("applicant");
        ResponseEntity<JsonNode> r = rest.exchange("/api/applications", HttpMethod.POST, new HttpEntity<>(requestFor("synthetic-identity"), bearer(applicant)), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode d = r.getBody().get("decision");
        assertThat(d.get("decision").asString()).isEqualTo("DECLINE");
        assertThat(d.get("creditLimit").isNull()).isTrue();
        // The applicant is told only that it is a decline; the gate's verdict and rules stay with the underwriter.
        assertThat(d.get("basis").isNull()).isTrue();
        assertThat(d.get("fraud").isNull()).isTrue();
        assertThat(d.get("verificationItems")).isEmpty();
        String id = r.getBody().get("id").asString();

        // An applicant cannot read the underwriter summary; an underwriter can, and can ask the copilot.
        assertThat(rest.exchange("/api/applications/" + id + "/summary", HttpMethod.GET, new HttpEntity<>(bearer(applicant)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String underwriter = login("underwriter");
        JsonNode full = rest.exchange("/api/applications/" + id, HttpMethod.GET, new HttpEntity<>(bearer(underwriter)), JsonNode.class).getBody();
        assertThat(full.get("decision").get("basis").asString()).isEqualTo("FRAUD_BLOCK");
        assertThat(full.get("decision").get("fraud").get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(full.get("decision").get("fraud").get("firedRules").size()).isGreaterThan(2);
        ResponseEntity<JsonNode> s = rest.exchange("/api/applications/" + id + "/summary", HttpMethod.GET, new HttpEntity<>(bearer(underwriter)), JsonNode.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s.getBody().get("recommendation").asString()).isIn("DECLINE", "REQUEST_DOCS", "REFER");

        ResponseEntity<JsonNode> c = rest.exchange("/api/copilot/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("applicationId", id, "question", "What happens when the fraud gate blocks an application?"), bearer(underwriter)), JsonNode.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(c.getBody().get("retrieved").size()).isGreaterThan(0);
        assertThat(c.getBody().get("answer").get("answer").asString()).isNotBlank();

        ResponseEntity<JsonNode> act = rest.exchange("/api/applications/" + id + "/actions", HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "CONFIRM", "reason", "Confirmed fraud-gate block after reviewing device history."), bearer(underwriter)), JsonNode.class);
        assertThat(act.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(act.getBody().get("actions")).hasSize(1);
    }

    @Test
    void bankDataWithoutConsentIsRejected() {
        String token = login("applicant");
        ObjectNode req = requestFor("prime-thick-file");
        ((ObjectNode) req.get("consent")).put("bankData", false);
        ResponseEntity<JsonNode> r = rest.exchange("/api/applications", HttpMethod.POST, new HttpEntity<>(req, bearer(token)), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
