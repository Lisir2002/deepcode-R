---
name: sample-status
description: 示例插件命令：输出当前环境状态摘要（容器/工作区/会话）
argument-hint: 可选：关注项（container / workspace / session）
---
请输出当前会话的环境状态摘要：
1. 容器模式（本地 PRoot / 远程 SSH）与可用性；
2. 当前工作区路径与目录结构概览；
3. 会话模式（PLAN / BUILD / AUTO）与当前任务目标。
若用户指定了关注项（$ARGUMENTS），优先围绕它展开。用中文简洁输出。
