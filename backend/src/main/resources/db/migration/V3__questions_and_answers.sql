CREATE TABLE question (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    error_text VARCHAR(2000),
    dependencies VARCHAR(2000),
    versions VARCHAR(2000),
    repository VARCHAR(2000),
    author_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (length(body) BETWEEN 1 AND 20000)
);
CREATE INDEX question_recent ON question (created_at DESC, id DESC);
CREATE TABLE tag (
    name VARCHAR(40) PRIMARY KEY
);
CREATE TABLE question_tag (
    question_id UUID NOT NULL REFERENCES question(id),
    tag_name VARCHAR(40) NOT NULL REFERENCES tag(name),
    PRIMARY KEY (question_id, tag_name)
);
CREATE INDEX question_tag_filter ON question_tag (tag_name, question_id);
CREATE TABLE answer (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES question(id),
    body TEXT NOT NULL,
    applicability VARCHAR(2000),
    verification VARCHAR(2000),
    author_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (length(body) BETWEEN 1 AND 20000)
);
CREATE INDEX answer_question ON answer (question_id, created_at, id);
-- A reservation and its product row commit together. ON CONFLICT waits for concurrent writers.
CREATE TABLE content_request (
    actor_id UUID NOT NULL REFERENCES app_user(id),
    operation VARCHAR(20) NOT NULL CHECK (operation IN ('create_question', 'create_answer')),
    request_id UUID NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_id UUID NOT NULL,
    PRIMARY KEY (actor_id, operation, request_id)
);
