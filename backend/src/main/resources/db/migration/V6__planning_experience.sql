ALTER TABLE request_revisions ADD COLUMN context_id CHAR(36) NULL,
    ADD CONSTRAINT revision_context FOREIGN KEY (context_id,session_id,owner_id) REFERENCES trip_contexts(id,session_id,owner_id);
UPDATE request_revisions r JOIN trip_contexts c ON c.session_id=r.session_id AND c.kind='PLANNING' SET r.context_id=c.id;
ALTER TABLE research_jobs ADD UNIQUE KEY job_session_scope (id,session_id,owner_id);
ALTER TABLE trip_contexts ADD COLUMN selected_job_id CHAR(36) NULL,
    ADD UNIQUE KEY selected_once (session_id,selected_job_id),
    ADD CONSTRAINT selected_job FOREIGN KEY (selected_job_id,session_id,owner_id) REFERENCES research_jobs(id,session_id,owner_id);
ALTER TABLE research_jobs ADD COLUMN focus VARCHAR(20) NOT NULL DEFAULT 'ALL',
    ADD CONSTRAINT job_focus CHECK (focus IN ('ALL','DESCRIPTION','STAY','TRANSPORT','ACTIVITY','PRICE','AVAILABILITY','POLICY'));
ALTER TABLE conversation_messages ADD COLUMN job_id CHAR(36) NULL,
    ADD CONSTRAINT message_job FOREIGN KEY (job_id,context_id,owner_id) REFERENCES research_jobs(id,context_id,owner_id);
CREATE TABLE research_images (
    id CHAR(36) NOT NULL PRIMARY KEY,
    source_id CHAR(36) NOT NULL,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    metadata JSON NOT NULL,
    CONSTRAINT image_source FOREIGN KEY (source_id,context_id,owner_id) REFERENCES source_snapshots(id,context_id,owner_id)
);
