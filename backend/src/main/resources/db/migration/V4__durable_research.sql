ALTER TABLE research_jobs
    ADD COLUMN request_key VARCHAR(100) NULL,
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD COLUMN retry_of CHAR(36) NULL,
    ADD COLUMN run_owner CHAR(36) NULL,
    ADD COLUMN started_at TIMESTAMP(6) NULL,
    ADD COLUMN outcome_code VARCHAR(64) NULL,
    ADD COLUMN search_provider VARCHAR(64) NOT NULL DEFAULT 'wikivoyage-v1',
    ADD COLUMN model_turns INT NOT NULL DEFAULT 0,
    ADD COLUMN searches INT NOT NULL DEFAULT 0,
    ADD COLUMN pages INT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY job_request_key (owner_id, request_key),
    ADD INDEX job_dispatch (status, created_at),
    ADD CONSTRAINT job_counters CHECK (model_turns >= 0 AND searches >= 0 AND pages >= 0),
    ADD CONSTRAINT job_retry FOREIGN KEY (retry_of) REFERENCES research_jobs(id);

ALTER TABLE source_snapshots
    ADD COLUMN requested_url TEXT NULL,
    ADD COLUMN response_status INT NULL,
    ADD COLUMN content_type VARCHAR(120) NULL,
    ADD COLUMN title VARCHAR(300) NULL;

CREATE TABLE research_tasks (
    id CHAR(36) NOT NULL PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT task_job FOREIGN KEY (job_id,context_id,owner_id) REFERENCES research_jobs(id,context_id,owner_id),
    CONSTRAINT task_kind CHECK (kind IN ('SEARCH','FETCH','MODEL')),
    CONSTRAINT task_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')),
    INDEX task_job_order (job_id,created_at,id)
);

CREATE TABLE research_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    data JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT event_job FOREIGN KEY (job_id,context_id,owner_id) REFERENCES research_jobs(id,context_id,owner_id),
    INDEX event_replay (job_id,id)
);
