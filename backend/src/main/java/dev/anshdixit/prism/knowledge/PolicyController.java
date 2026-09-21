package dev.anshdixit.prism.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/policy")
@Validated
public class PolicyController {

    private final PolicySearchService policy;

    public PolicyController(PolicySearchService policy) {
        this.policy = policy;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('UNDERWRITER','ADMIN')")
    public List<PolicySearchService.Chunk> search(@RequestParam @NotBlank @Size(max = 300) String q) {
        return policy.search(q, 5);
    }
}
