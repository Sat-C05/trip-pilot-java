-- Presentation metadata is independent of immutable research and booking records.
ALTER TABLE research_jobs
    ADD COLUMN card_title VARCHAR(120) NULL,
    ADD COLUMN card_notes VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN card_deleted_at TIMESTAMP(6) NULL;
