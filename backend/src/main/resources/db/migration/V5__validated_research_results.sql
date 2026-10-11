CREATE TABLE validated_research_results (
    job_id CHAR(36) NOT NULL PRIMARY KEY,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    validator_version VARCHAR(64) NOT NULL,
    result JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT result_job FOREIGN KEY (job_id, context_id, owner_id)
        REFERENCES research_jobs(id, context_id, owner_id)
);
