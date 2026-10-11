CREATE TABLE booking_quotes (
    id CHAR(36) PRIMARY KEY,
    context_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    revision_id CHAR(36) NOT NULL,
    provider_url VARCHAR(500) NOT NULL,
    quote JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY booking_quote_scope(id,context_id,owner_id),
    CONSTRAINT booking_quote_context FOREIGN KEY(context_id,session_id,owner_id) REFERENCES trip_contexts(id,session_id,owner_id),
    CONSTRAINT booking_quote_revision FOREIGN KEY(revision_id,session_id,owner_id) REFERENCES request_revisions(id,session_id,owner_id)
);
CREATE TABLE booking_attempts (
    id CHAR(36) PRIMARY KEY,
    quote_id CHAR(36) NOT NULL UNIQUE,
    context_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    ceiling DECIMAL(12,2) NOT NULL,
    authorized_until TIMESTAMP(6) NOT NULL,
    state VARCHAR(24) NOT NULL,
    provider_key CHAR(36) NOT NULL UNIQUE,
    hold_id VARCHAR(100) NULL,
    booking_id VARCHAR(100) NULL,
    outcome VARCHAR(40) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY booking_authorization_key(owner_id,request_key),
    CONSTRAINT attempt_quote FOREIGN KEY(quote_id,context_id,owner_id) REFERENCES booking_quotes(id,context_id,owner_id),
    CONSTRAINT booking_ceiling CHECK(ceiling>0),
    CONSTRAINT booking_state CHECK(state IN ('AUTHORIZED','SUBMITTING','UNCERTAIN','CONFIRMED','REJECTED','REVOKED'))
);
