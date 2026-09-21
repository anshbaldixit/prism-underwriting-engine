package dev.anshdixit.prism.scoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    Optional<Decision> findFirstByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    List<Decision> findAllByOrderByCreatedAtDesc();

    @Query("select d.decision as decision, count(d) as n from Decision d group by d.decision")
    List<DecisionCount> countByDecision();

    interface DecisionCount {
        String getDecision();
        long getN();
    }
}
