ALTER TABLE question ADD COLUMN search_revision BIGINT NOT NULL DEFAULT 1;

CREATE TABLE search_generation (
    id VARCHAR(64) PRIMARY KEY,
    profile TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 16000),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX search_one_active_generation ON search_generation (active) WHERE active;

CREATE TABLE search_work (
    question_id UUID PRIMARY KEY REFERENCES question(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMPTZ,
    failure VARCHAR(40)
);
CREATE INDEX search_work_due ON search_work (next_attempt_at);

CREATE TABLE search_document (
    generation_id VARCHAR(64) NOT NULL REFERENCES search_generation(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    answer_id UUID REFERENCES answer(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    embedding DOUBLE PRECISION ARRAY NOT NULL,
    PRIMARY KEY (generation_id, question_id, ordinal)
);
