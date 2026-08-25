---
name: 60-ai-conduct
order: 60
whenToUse: AI Agent 行为纪律（Always/Ask First/Never、意图问判、行为模式、SOP/Skill 边界）需按步骤执行时
source: prompts/（行为纪律：15-project-rules、20-coding-discipline、50-safety、40-approach 等）
---

# SOP-60 AI 行为纪律（AI Conduct）

> 权威源：`prompts/` 行为纪律资产（15-project-rules、20-coding-discipline、50-safety、40-approach 等）。本 SOP 将行为规则步骤化，供 AI 每轮自检照做。

## 1. 边界规则自检（Always / Ask First / Never）

- **操作**：每轮开始/关键动作前对照边界规则三分类自检。
- **判定**：
  - Always：必做项（中文回复、专用工具、编译验证、push 前单测、资产同步）；
  - Ask First：破坏性操作先询问（删文件/分支/Tag 发版/架构重构/改 schema）；
  - Never：禁止项（`.kt` 硬编码中文文案、功能分支打 Tag、迁移 SQL 含 `;`、改高 targetSdk、写敏感信息、擅自改 AGENTS.md）。
- **产出**：行为落在纪律内；违规动作改道或先说明。

## 2. 意图问判三问（D0）

- **操作**：每轮开始内省核对三问。
- **判定**：① 我的理解（用户到底要什么）② 我的拆解思路 ③ 应落哪个形态（goal/plan/jobs/schedule/playbook/普通对话）；低置信时主动澄清不猜着做。
- **产出**：理解与形态确定；不确定则调 AskUserQuestion 澄清。

## 3. 行为模式纪律（D0）

- **操作**：按当前 behaviorMode（design/execute/research/chat）行事。
- **判定**：design/research 模式只出方案不写文件；plan 形态强制 design；`/mode` 显式切换锁定到解除。
- **产出**：模式行为一致；违反则按 guard 提醒纠正。

## 4. SOP / Skill 边界判定（D4，双判据）

- **操作**：判断任务适用 SOP 还是 Skill。
- **判定**：主判据按适用范围——SOP = 仓库内固定操作流程（发版/迁移/提交等，绑项目语义，摘要常驻注入）；Skill = 通用可复用技能（用户可增删的技能中心）。辅助判据按步骤化程度——SOP 严格编号步骤（操作+判定+产出）；Skill 可非步骤化（知识/能力类）。双判据同时满足才归 SOP。
- **产出**：归对类别并按对应方式取用（`loadSop` 取 SOP 正文 / `loadSkill` 取技能正文）；拿不准优先按 SOP 处理并说明。

## 5. 分层规则取用（D3）

- **操作**：系统提示「分层规则摘要」中的规则名与当前任务相关、需看完整条款时，用 `load_rule` 取正文。
- **判定**：名称与摘要清单一致；不凭记忆臆造规则内容。
- **产出**：按规则完整正文行事。
