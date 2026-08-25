---
name: 30-asset-sync
order: 30
whenToUse: 改动 AI 工作流 / 功能 / UI / 代码结构，需要同步 prompts、docs、strings.xml 或模块文档时
source: AGENTS.md「资产同步纪律」
---

# SOP-30 资产同步（Asset Sync）

> 权威源：`AGENTS.md`「资产同步纪律」。prompts / docs / strings.xml / 模块文档四类变更必须同步。

## 1. 判定改动类别

- **操作**：按本次改动归类（AI 工作流 / 功能工具 / UI / 代码结构）。
- **判定**：命中以下任一类别 → 进入对应步骤；纯 `.md` 文档改动且无行为变化可跳过。
- **产出**：得出需同步的资产类别清单；漏判会导致 pre-commit 或后续会话知识过期。

## 2. AI 工作流改动 → 检查 prompts

- **操作**：工具新增/删除/重命名/参数签名变化、agent 行为变化、提示词逻辑调整 → 检查 `app/src/main/assets/prompts/` 下对应提示词是否需要同步。
- **判定**：模型看到的工具定义与行为说明与实际一致；不存在对应提示词文件则新建。
- **产出**：prompts 同步完成；不一致则补改。

## 3. 功能/工具变化 → 检查 docs

- **操作**：功能新增/删除/行为变化或工具变更 → 检查 `app/src/main/assets/docs/` 下使用文档。
- **判定**：新功能有使用说明、工具行为变化有提示。
- **产出**：docs 同步完成；缺文档则新建。

## 4. UI 变化 → 必须更新使用文档 + strings.xml

- **操作**：新增页面/改交互/调布局/改文案 → 同步更新 `app/src/main/assets/docs/` 对应文档；用户可见中文文案提取为 string resource 写入 `values/strings.xml`。
- **判定**：界面说明与实际一致；`.kt` 中无硬编码用户可见中文文案（用 `stringResource`/`context.getString` 引用）。
- **产出**：UI 文档与文案资源同步完成；硬编码文案则提取。

## 5. 代码结构变化 → 必须同步模块文档

- **操作**：功能新增/删除/行为变化/目录结构调整 → 同步 `docs/modules/<module>.md`；`feature/` 下新增模块时实时新建文档并在 `docs/modules/README.md` 登记。
- **判定**：每个 feature 模块都有对应文档、无孤儿文档（pre-commit 自动校验）。
- **产出**：模块文档同步完成；被 pre-commit 阻断则补文档。
