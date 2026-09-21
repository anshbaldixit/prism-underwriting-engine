package dev.anshdixit.prism.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AiInvocationRepository extends JpaRepository<AiInvocation, UUID> {

    @Query("select a.provider as provider, a.task as task, count(a) as calls, avg(a.latencyMs) as avgLatencyMs, " +
            "sum(case when a.outputValid = true then 1 else 0 end) as validCount, " +
            "sum(case when a.fallbackUsed = true then 1 else 0 end) as fallbackCount " +
            "from AiInvocation a group by a.provider, a.task")
    List<InvocationStats> stats();

    interface InvocationStats {
        String getProvider();
        String getTask();
        long getCalls();
        Double getAvgLatencyMs();
        long getValidCount();
        long getFallbackCount();
    }
}
