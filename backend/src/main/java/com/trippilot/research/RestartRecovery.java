package com.trippilot.research;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Single-instance baseline: do not replay an interrupted run or any action. */
@Component
public class RestartRecovery {
    private final JdbcTemplate jdbc;
    public RestartRecovery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public int interruptOrphanedRuns() {
        jdbc.update("INSERT INTO research_events(job_id,context_id,owner_id,event_type,data) SELECT id,context_id,owner_id,'JOB_INTERRUPTED',JSON_OBJECT('state','INTERRUPTED','code','SERVER_RESTARTED') FROM research_jobs WHERE status IN ('RUNNING','CANCEL_REQUESTED')");
        jdbc.update("UPDATE research_tasks SET status='INTERRUPTED',outcome_code='SERVER_RESTARTED',updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING' AND job_id IN (SELECT id FROM research_jobs WHERE status IN ('RUNNING','CANCEL_REQUESTED'))");
        return jdbc.update("UPDATE research_jobs SET status='INTERRUPTED',outcome_code='SERVER_RESTARTED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE status IN ('RUNNING','CANCEL_REQUESTED')");
    }

}
