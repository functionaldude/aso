ALTER TABLE question ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE question ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE question ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE answer ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE answer ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE answer ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
-- The composite FK prevents accepting an answer from another question even outside the service.
ALTER TABLE answer ADD CONSTRAINT answer_parent_id UNIQUE (question_id, id);
ALTER TABLE question ADD COLUMN accepted_answer_id UUID;
ALTER TABLE question ADD CONSTRAINT question_accepted_answer FOREIGN KEY (id, accepted_answer_id) REFERENCES answer(question_id, id);
CREATE TABLE content_comment (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES question(id),
    answer_id UUID,
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 20000),
    author_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (question_id, answer_id) REFERENCES answer(question_id, id)
);
CREATE INDEX comment_chronology ON content_comment (question_id, answer_id, created_at, id);
ALTER TABLE content_request DROP CONSTRAINT IF EXISTS content_request_operation_check;
ALTER TABLE content_request ADD CONSTRAINT content_request_operation_check CHECK (operation IN ('create_question', 'create_answer', 'create_comment'));
-- Snapshots contain versions, visibility, acceptance and content fingerprints, never full prose/tokens.
CREATE TABLE content_audit (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    action VARCHAR(20) NOT NULL CHECK (action IN ('edit', 'accept', 'hide', 'restore')),
    target_kind VARCHAR(10) NOT NULL CHECK (target_kind IN ('question', 'answer', 'comment')),
    target_id UUID NOT NULL,
    question_id UUID NOT NULL REFERENCES question(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    before_state TEXT NOT NULL,
    after_state TEXT NOT NULL
);
CREATE INDEX audit_question ON content_audit (question_id, occurred_at, id);
