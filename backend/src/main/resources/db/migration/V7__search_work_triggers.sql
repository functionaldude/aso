CREATE FUNCTION search_queue_question() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    INSERT INTO search_work (question_id, revision)
    VALUES (NEW.id, NEW.search_revision)
    ON CONFLICT (question_id) DO UPDATE
    SET revision = EXCLUDED.revision, attempts = 0, next_attempt_at = CURRENT_TIMESTAMP,
        lease_until = NULL, failure = NULL;
    RETURN NEW;
END;
$$;
CREATE FUNCTION search_bump_revision() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    NEW.search_revision := OLD.search_revision + 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER search_question_revision BEFORE UPDATE ON question
    FOR EACH ROW EXECUTE FUNCTION search_bump_revision();
CREATE TRIGGER search_question_queue AFTER INSERT OR UPDATE ON question
    FOR EACH ROW EXECUTE FUNCTION search_queue_question();

CREATE FUNCTION search_touch_parent() RETURNS TRIGGER LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        UPDATE question SET search_revision = search_revision WHERE id = OLD.question_id;
        RETURN OLD;
    END IF;
    UPDATE question SET search_revision = search_revision WHERE id = NEW.question_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER search_answer_queue AFTER INSERT OR UPDATE OR DELETE ON answer
    FOR EACH ROW EXECUTE FUNCTION search_touch_parent();
CREATE TRIGGER search_tag_queue AFTER INSERT OR UPDATE OR DELETE ON question_tag
    FOR EACH ROW EXECUTE FUNCTION search_touch_parent();
