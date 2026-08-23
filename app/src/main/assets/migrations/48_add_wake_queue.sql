-- 统一唤醒队列（WakeQueue）SCHEMA 48：
--   单一 Room 队列承载 #4 hook 后台审查结果与 #10 耗时任务结果（一套机制两处消费），
--   下轮会话开始前注入 system-reminder + 消费确认（防重复/防丢失），Room 持久化支撑
--   App 被杀后下次启动重扫待注入队列继续唤醒。
--
-- 注意：列定义与 Room @Entity 导出 schema 完全一致（含 NOT NULL、无 DEFAULT），
-- 否则 MigrationSchemaConsistencyTest 闸门会 fail（Room TableInfo 校验失败 → 触发 Funnel 抢救）。
CREATE TABLE IF NOT EXISTS wake_queue (
    wake_id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL,
    source TEXT NOT NULL,
    type TEXT NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);
