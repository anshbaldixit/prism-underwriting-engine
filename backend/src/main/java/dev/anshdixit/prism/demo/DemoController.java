package dev.anshdixit.prism.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Serves the synthetic demo personas (with their three-month statements) that the UI can load into the form. */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final JsonNode personas;

    public DemoController(JsonMapper mapper) {
        try (InputStream in = new ClassPathResource("demo/personas.json").getInputStream()) {
            this.personas = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/personas")
    @PreAuthorize("hasAnyRole('APPLICANT','UNDERWRITER','ADMIN')")
    public JsonNode personas() {
        return personas;
    }
}
