-- 长输出分块存储 v49：为 agent_messages 增加分块字段。
-- 超长消息（>CHUNK_SIZE=100k 字符）按块拆分落库：主行（chunk_index=0）携带全部元数据，
-- 续块行（chunk_index=1..N-1）仅携带 content；全组共享同一 timestamp 保证查询邻接。
-- 读取侧（buildHistory / UI 流）按 chunk_index 序拼接，单行永不超过 CursorWindow 限制，
-- 从根上杜绝 SQLiteBlobTooBigException 导致的「无声闪退」，同时不再截断长输出。
-- 历史消息（升级前）chunkGroupId 为空串、chunkIndex=0，等价于单行非分块消息。
-- 列名用 camelCase（chunkGroupId / chunkIndex）：实体未显式指定列名，Room 默认以字段名作列名，
-- 与 taskId / inputTokens 等既有列命名一致，避免迁移后 TableInfo 校验失败。
ALTER TABLE agent_messages ADD COLUMN chunkGroupId TEXT NOT NULL DEFAULT '';
ALTER TABLE agent_messages ADD COLUMN chunkIndex INTEGER NOT NULL DEFAULT 0;
