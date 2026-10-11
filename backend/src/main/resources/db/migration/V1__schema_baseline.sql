-- Establish migration history without prematurely defining domain entities.
-- Domain schema and ownership constraints are a Phase 1 contract task.
CREATE TABLE schema_baseline (
    id SMALLINT NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

INSERT INTO schema_baseline (id, description)
VALUES (1, 'TripPilot initial schema baseline');
