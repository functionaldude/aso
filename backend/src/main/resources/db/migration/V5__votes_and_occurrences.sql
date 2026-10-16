-- An active vote is unique per developer/target; undo deletes only that developer's row.
CREATE TABLE content_vote (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES question(id),
    answer_id UUID,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    verification VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (answer_id, question_id) REFERENCES answer(id, question_id),
    UNIQUE (actor_id, answer_id),
    CHECK (verification IS NULL OR length(trim(verification)) BETWEEN 1 AND 500),
    CHECK (channel <> 'mcp' OR answer_id IS NULL OR verification IS NOT NULL)
);
CREATE UNIQUE INDEX question_vote_actor ON content_vote (question_id, actor_id) WHERE answer_id IS NULL;
CREATE INDEX vote_target_page ON content_vote (question_id, answer_id, created_at, id);
-- Event IDs belong to an authenticated developer, across all questions and transports.
CREATE TABLE occurrence (
    reporter_id UUID NOT NULL REFERENCES app_user(id),
    event_id UUID NOT NULL,
    question_id UUID NOT NULL REFERENCES question(id),
    channel VARCHAR(3) NOT NULL CHECK (channel IN ('web', 'mcp')),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (reporter_id, event_id)
);
CREATE INDEX occurrence_question_window ON occurrence (question_id, created_at, reporter_id);
