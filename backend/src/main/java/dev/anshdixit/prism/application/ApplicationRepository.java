package dev.anshdixit.prism.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    List<Application> findByStatusInOrderByCreatedAtDesc(List<Application.Status> statuses);

    List<Application> findAllByOrderByCreatedAtDesc();

    List<Application> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    /** Real device velocity: how many other applications this device submitted in the window. */
    @Query("select count(a) from Application a where a.deviceId = :deviceId and a.createdAt >= :since")
    long countByDeviceSince(String deviceId, Instant since);

    long countByNationalIdHash(String nationalIdHash);
}
