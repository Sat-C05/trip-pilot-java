CREATE TABLE app_users (
    id CHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(191) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE planning_sessions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY session_owner (id, owner_id),
    CONSTRAINT session_user FOREIGN KEY (owner_id) REFERENCES app_users(id)
);

CREATE TABLE request_revisions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    revision_no INT NOT NULL,
    requirements JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY session_revision (session_id, revision_no),
    UNIQUE KEY revision_scope (id, session_id, owner_id),
    CONSTRAINT revision_session FOREIGN KEY (session_id, owner_id) REFERENCES planning_sessions(id, owner_id),
    CONSTRAINT revision_positive CHECK (revision_no > 0)
);

CREATE TABLE trip_contexts (
    id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    selection_snapshot JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY context_scope (id, session_id, owner_id),
    CONSTRAINT context_session FOREIGN KEY (session_id, owner_id) REFERENCES planning_sessions(id, owner_id),
    CONSTRAINT context_kind CHECK (kind IN ('PLANNING', 'SELECTED')),
    CONSTRAINT selected_snapshot CHECK ((kind = 'SELECTED' AND selection_snapshot IS NOT NULL) OR (kind = 'PLANNING' AND selection_snapshot IS NULL))
);

CREATE TABLE research_jobs (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    request_revision_id CHAR(36) NOT NULL,
    model_snapshot JSON NOT NULL,
    harness_snapshot JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    trace_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY job_scope (id, context_id, owner_id),
    CONSTRAINT job_revision FOREIGN KEY (request_revision_id, session_id, owner_id) REFERENCES request_revisions(id, session_id, owner_id),
    CONSTRAINT job_context FOREIGN KEY (context_id, session_id, owner_id) REFERENCES trip_contexts(id, session_id, owner_id),
    CONSTRAINT job_status CHECK (status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','CANCELLED','SUCCEEDED','PARTIAL','FAILED','INTERRUPTED')),
    CONSTRAINT job_version CHECK (version >= 0)
);

CREATE TABLE source_snapshots (
    id CHAR(36) NOT NULL PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    final_url TEXT NOT NULL,
    retrieved_at TIMESTAMP(6) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    UNIQUE KEY source_scope (id, context_id, owner_id),
    CONSTRAINT source_job FOREIGN KEY (job_id, context_id, owner_id) REFERENCES research_jobs(id, context_id, owner_id)
);

CREATE TABLE evidence_passages (
    id CHAR(36) NOT NULL PRIMARY KEY,
    source_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    passage TEXT NOT NULL,
    CONSTRAINT passage_source FOREIGN KEY (source_id, context_id, owner_id) REFERENCES source_snapshots(id, context_id, owner_id),
    CONSTRAINT passage_offsets CHECK (start_offset >= 0 AND end_offset > start_offset)
);

CREATE TABLE conversation_messages (
    id CHAR(36) NOT NULL PRIMARY KEY,
    context_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT message_context FOREIGN KEY (context_id, session_id, owner_id) REFERENCES trip_contexts(id, session_id, owner_id),
    CONSTRAINT message_role CHECK (role IN ('USER','ASSISTANT'))
);
