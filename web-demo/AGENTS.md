# AGENTS.md

本文件是 web-demo 项目的 **AI 协同开发规范**（给 AI 的"README"），任意 AI Agent 在本目录工作时必须遵守。

## 项目概览

web-demo 是一个极简的 **HTML + PHP** 演示项目：一个静态前端页面通过 `fetch` 调用 PHP 接口获取数据并渲染展示，用于快速查看前端调用后端的完整效果。

## 技术栈与运行

| 类别 | 说明 |
|------|------|
| 前端 | 原生 HTML / CSS / JavaScript（无构建步骤） |
| 后端 | PHP 8+（纯脚本接口，无框架） |
| 运行 | PHP 内置服务器：`php -S 0.0.0.0:8000 -t .` |

## 关键命令

```bash
# 启动本地服务器（默认端口 8000）
php -S 0.0.0.0:8000 -t .
# 验证接口
curl http://localhost:8000/api.php
```

## 目录结构

```
web-demo/
├── index.html   # 前端页面（fetch 调 api.php 渲染数据）
├── api.php      # 后端接口（返回 JSON 数据）
├── AGENTS.md    # 本规范
├── README.md    # 项目说明
└── .gitignore   # 忽略规则
```

## 边界规则

### Always（必须做）

- 所有用户可见文案使用中文（本演示项目为中文场景）。
- 前端与后端**接口契约保持同步**：改动 `api.php` 返回字段时，必须同步更新 `index.html` 的渲染逻辑。
- 提交前跑 `php -l api.php` 校验语法。

### Ask First（先询问确认）

- 引入任何依赖/框架（当前刻意保持零依赖）。
- 破坏性操作：删除文件 / force push / 修改本文件。

### Never（禁止）

- **禁止在 PHP 中直接拼接未过滤的用户输入进 SQL 或 `shell_exec`**（本项目无数据库，但接口参数必须做白名单/转义，防注入）。
- **禁止硬编码绝对路径**（沙箱/本机路径不可移植）。
- **禁止提交敏感信息**（token / 密码 / 密钥）。

## Git 提交规范

采用 Conventional Commits：`feat(xxx): 描述` / `fix(xxx): 描述` / `docs: 描述`。

## 维护本文件

本文件是活文档，AI 发现规则与实际做法不一致时应主动提示维护者更新。
