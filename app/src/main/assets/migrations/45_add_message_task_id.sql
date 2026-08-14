-- 任务手风琴：为 agent_messages 增加 taskId 列。
-- 每次用户请求（executeAgentRequestStream）生成一个 taskId，该请求产出的所有消息
-- （用户消息 / 助手回复 / 工具调用 / 思考过程）都归入同一任务分组。
-- 历史消息（升级前）taskId 为空串，UI 按「历史对话」扁平展示，不参与任务分组。
ALTER TABLE agent_messages ADD COLUMN taskId TEXT NOT NULL DEFAULT '';
