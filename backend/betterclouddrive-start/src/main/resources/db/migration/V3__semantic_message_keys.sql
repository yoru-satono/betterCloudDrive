-- Message keys are a public protocol. Convert every underscore-delimited segment
-- to lowerCamelCase in the columns that persist those keys.
CREATE OR REPLACE FUNCTION bcd_semantic_message_key(value TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    segment TEXT;
    word TEXT;
    segment_result TEXT;
    result TEXT := '';
    first_segment BOOLEAN := TRUE;
    first_word BOOLEAN;
BEGIN
    FOREACH segment IN ARRAY string_to_array(value, '.') LOOP
        segment_result := '';
        first_word := TRUE;
        FOREACH word IN ARRAY string_to_array(segment, '_') LOOP
            IF first_word THEN
                segment_result := lower(word);
                first_word := FALSE;
            ELSIF word <> '' THEN
                segment_result := segment_result || upper(left(word, 1)) || lower(substr(word, 2));
            END IF;
        END LOOP;
        result := result || CASE WHEN first_segment THEN '' ELSE '.' END || segment_result;
        first_segment := FALSE;
    END LOOP;
    RETURN result;
END;
$$;

UPDATE notifications
SET title_key = bcd_semantic_message_key(title_key),
    content_key = bcd_semantic_message_key(content_key)
WHERE title_key LIKE '%\_%' ESCAPE '\' OR content_key LIKE '%\_%' ESCAPE '\';

UPDATE team_tasks
SET title_key = bcd_semantic_message_key(title_key),
    result_message_key = CASE WHEN result_message_key IS NULL THEN NULL ELSE bcd_semantic_message_key(result_message_key) END,
    error_message_key = CASE WHEN error_message_key IS NULL THEN NULL ELSE bcd_semantic_message_key(error_message_key) END
WHERE title_key LIKE '%\_%' ESCAPE '\'
   OR result_message_key LIKE '%\_%' ESCAPE '\'
   OR error_message_key LIKE '%\_%' ESCAPE '\';

UPDATE team_event_outbox
SET message_key = bcd_semantic_message_key(message_key)
WHERE message_key LIKE '%\_%' ESCAPE '\';

DROP FUNCTION bcd_semantic_message_key(TEXT);
