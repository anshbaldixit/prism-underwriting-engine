package dev.anshdixit.prism.copilot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final CopilotService copilot;

    public CopilotController(CopilotService copilot) {
        this.copilot = copilot;
    }

    public record AskRequest(UUID applicationId, @NotBlank @Size(max = 600) String question) {
    }

    @PostMapping("/ask")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public CopilotService.Answer ask(@Valid @RequestBody AskRequest req) {
        return copilot.ask(req.applicationId(), req.question());
    }
}
