---
kind: playbook
name: feature-dev
description: 新功能开发全流程（发现→设计文档[联动Spec]→分支→实施→冒烟→单测→提交→合入）
stages:
  - name: 发现与范围
    description: 明确需求、现状与改动面，输出理解与拆解思路；不写代码
    agents: []
    sop: [60-ai-conduct]
    gates: approval
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 60000
  - name: 设计文档
    description: 新增/复杂改动先出设计文档到 docs/plan-docs/（<名称>-design.md，含评审状态行），走 Spec 评审；简单改动跳过
    agents: []
    sop: [60-ai-conduct]
    gates: approval
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 120000
  - name: 分支
    description: 新建 feat/<主题> 分支；确认分支命名，避免不同主题混在同一分支
    agents: []
    sop: [40-git-commit]
    gates: approval
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 60000
  - name: 实施
    description: 按设计/方案实施编码；编译型代码（.kt/.gradle.kts/AndroidManifest.xml）改动遵循最小改动与纪律，UI 文案走 strings.xml
    agents: []
    sop: [30-asset-sync, 60-ai-conduct]
    gates: auto
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 300000
  - name: 冒烟
    description: 编译验证 assembleDebug 通过（debug buildType 快，不跑 R8）；失败则修复再验
    agents: []
    sop: [50-troubleshooting]
    gates: auto
    async: true
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 300000
  - name: 单元测试
    description: push 前必跑 testReleaseUnitTest（release classpath 与 CI 门禁同款），全部通过
    agents: []
    sop: [50-troubleshooting]
    gates: auto
    async: true
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 300000
  - name: 提交
    description: 按 Conventional Commits 提交（type(scope): subject）；资产同步纪律（prompts/docs/strings/模块文档）随改随同步
    agents: []
    sop: [40-git-commit]
    gates: approval
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 120000
  - name: 合入
    description: 合并回 main 并清理分支（git branch -d，已推送同步删远端）；确认无冲突
    agents: []
    sop: [40-git-commit]
    gates: approval
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 60000
---

# Playbook: feature-dev（新功能开发全流程）

> 作用：多阶段大任务按剧本推进不跳步。阶段由 `playbook_advance(action=done|fail)` 显式推进；
> `gates: approval` 阶段需用户批准（`!` 标记可跳过）；阶段内无 `agents[]` 声明时由主模型按
> 阶段注入的目标执行。设计文档阶段联动 Spec（docs/plan-docs/ 评审状态行）。
