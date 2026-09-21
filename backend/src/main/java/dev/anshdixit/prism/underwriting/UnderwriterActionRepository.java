package dev.anshdixit.prism.underwriting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UnderwriterActionRepository extends JpaRepository<UnderwriterAction, UUID> {
    List<UnderwriterAction> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);
    long countByActionIn(List<UnderwriterAction.Action> actions);
}
