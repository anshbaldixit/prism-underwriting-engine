package dev.anshdixit.prism.application;

import dev.anshdixit.prism.underwriting.UnderwriterAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * API-first surface for applications. Roles:
 * APPLICANT submits and sees own applications (identity shown in full to themselves);
 * UNDERWRITER / ADMIN see the queue, full detail, the AI summary, and can record actions.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('APPLICANT','UNDERWRITER','ADMIN')")
    public ApplicationDetail submit(@Valid @RequestBody SubmitApplicationRequest req, Authentication auth) {
        return service.submit(req, auth.getName());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('APPLICANT','UNDERWRITER','ADMIN')")
    public List<ApplicationService.ApplicationSummary> list(@RequestParam(required = false) List<Application.Status> status, Authentication auth) {
        boolean applicant = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_APPLICANT"));
        return service.list(status, applicant ? auth.getName() : null);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('APPLICANT','UNDERWRITER','ADMIN')")
    public ApplicationDetail get(@PathVariable UUID id, Authentication auth) {
        boolean applicant = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_APPLICANT"));
        ApplicationDetail d = service.get(id, true);
        if (applicant && !auth.getName().equals(ownerOf(d))) {
            throw new org.springframework.security.access.AccessDeniedException("Not your application");
        }
        return d;
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public JsonNode summary(@PathVariable UUID id) {
        return service.underwriterSummary(id);
    }

    public record ActionRequest(@NotNull UnderwriterAction.Action action, @NotBlank @Size(min = 20, max = 600) String reason) {
    }

    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public ApplicationDetail act(@PathVariable UUID id, @Valid @RequestBody ActionRequest req, Authentication auth) {
        return service.recordAction(id, auth.getName(), req.action(), req.reason());
    }

    private String ownerOf(ApplicationDetail d) {
        return service.ownerOf(d.id());
    }
}
