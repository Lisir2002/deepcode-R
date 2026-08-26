---
kind: playbook
name: bug-fix
description: Bug 修复全流程（复现→根因→修复→回归→提交）
stages:
  - name: 复现
    description: 复现 bug，确认现象、触发条件与影响范围；只读调研，输出复现结论
    agents: []
    sop: [50-troubleshooting, 60-ai-conduct]
    gates: auto
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 120000
  - name: 根因
    description: 定位根因，输出根因分析（现象→链路→根因），确认修复目标；不写修复代码
    agents: []
    sop: [50-troubleshooting]
    gates: approval
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 180000
  - name: 修复
    description: 最小改动修复；编译型代码（.kt/.gradle.kts/AndroidManifest.xml）遵循最小改动与纪律，UI 文案走 strings.xml
    agents: []
    sop: [30-asset-sync, 60-ai-conduct]
    gates: auto
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 300000
  - name: 回归
    description: 编译验证 assembleDebug 通过 + 回归测试（相关单测/链路）；失败则修复再验
    agents: []
    sop: [50-troubleshooting]
    gates: auto
    async: true
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 300000
  - name: 提交
    description: 按 Conventional Commits 提交（fix(scope): subject）；资产同步纪律（prompts/docs/strings/模块文档）随改随同步
    agents: []
    sop: [40-git-commit]
    gates: approval
    async: false
    sandbox: workspace_write
    seed: spawn
    guards:
      timeout: 120000
---

# Playbook: bug-fix（Bug 修复全流程）

> 作用：多阶段修复任务按剧本推进不跳步。阶段由 `playbook_advance(action=done|fail)` 显式推进；
> `gates: approval` 阶段需用户批准（`!` 标记可跳过）；复现/根因阶段只读，修复阶段才允许写工作区；
> 阶段内无 `agents[]` 声明时由主模型按阶段注入的目标执行。
