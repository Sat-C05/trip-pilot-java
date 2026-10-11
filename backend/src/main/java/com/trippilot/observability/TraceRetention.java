package com.trippilot.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TraceRetention {
    private final JdbcTemplate jdbc;
    public TraceRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Events only; evidence/revisions remain intact. Live jobs are never cleaned. */
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    public int clean() {
        return jdbc.update("DELETE e FROM research_events e JOIN research_jobs j ON j.id=e.job_id WHERE j.status NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND e.created_at < TIMESTAMPADD(DAY,-GREATEST(1,LEAST(90,COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(j.harness_snapshot,'$.traceRetentionDays')) AS SIGNED),14))),CURRENT_TIMESTAMP(6))");
    }
}
