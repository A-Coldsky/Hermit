-- 同一实例的同一 event_id 只保留第一条回滚事件。
-- 已有行用主键拼出不重复的值；新写入必须自带 event_id。

ALTER TABLE rollback_events ADD COLUMN event_id VARCHAR(64) NOT NULL DEFAULT '';

UPDATE rollback_events SET event_id = CONCAT('legacy-', id);

ALTER TABLE rollback_events ALTER COLUMN event_id DROP DEFAULT;

CREATE UNIQUE INDEX uk_rollback_events_instance_event
    ON rollback_events (instance_id, event_id);
