---
kind: playbook
name: code-review
description: 代码审查全流程（获取 diff→分类审查→聚合分级→提交审查结论）
stages:
  - name: 获取 diff
    description: 明确审查范围（提交/分支/文件），获取改动 diff 并核对统计；只读不改动
    agents: []
    sop: [60-ai-conduct]
    gates: auto
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 60000
  - name: 分类审查
    description: 按改动类型（逻辑/样式/测试/资源）分工逐项审查，标注问题与疑似点，记录问题清单
    agents: []
    sop: [60-ai-conduct]
    gates: auto
    async: true
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 180000
  - name: 聚合分级
    description: 汇总全部审查发现，按严重程度分级（阻断/建议/可选）输出审查报告，附文件清单与定位
    agents: []
    sop: [60-ai-conduct]
    gates: approval
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 120000
  - name: 提交审查结论
    description: 输出结构化审查结论（问题清单 + 文件定位 + 分级建议），供用户决策是否修复
    agents: []
    sop: [40-git-commit]
    gates: approval
    async: false
    sandbox: read_only
    seed: spawn
    guards:
      timeout: 60000
---

# Playbook: code-review（代码审查全流程）

> 作用：多阶段审查任务按剧本推进不跳步。阶段由 `playbook_advance(action=done|fail)` 显式推进；
> `gates: approval` 阶段需用户批准（`!` 标记可跳过）；审查过程只读（read_only），不修改代码；
> 阶段内无 `agents[]` 声明时由主模型按阶段注入的目标执行。
