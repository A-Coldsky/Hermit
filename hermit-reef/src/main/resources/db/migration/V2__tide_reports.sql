-- 同一实例的同一 trace 只保留第一条失败上报。
-- acknowledged_by 记下确认告警的管理员。

CREATE UNIQUE INDEX uk_failure_reports_instance_trace
    ON failure_reports (instance_id, trace_id);

ALTER TABLE alerts ADD COLUMN acknowledged_by VARCHAR(128);
