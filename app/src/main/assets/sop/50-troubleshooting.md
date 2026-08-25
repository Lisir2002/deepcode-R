---
name: 50-troubleshooting
order: 50
whenToUse: 遇到构建失败 / 数据库迁移失败 / 容器无法执行 / 提交被 hooks 阻断等常见问题时
source: AGENTS.md「常见坑」
---

# SOP-50 排障（Troubleshooting）

> 权威源：`AGENTS.md`「常见坑」。按症状表逐条对照，命中即按处理修复。

## 1. 数据库迁移启动即失败

- **操作**：检查迁移 SQL 是否含字面量 `;`。
- **判定**：SQL 字面量含 `;` 会被切分器误切。
- **产出**：用 `char(59)` 代替字面量分号；未命中则查迁移文件本身。

## 2. 构建命令报错 / 找不到任务

- **操作**：核对使用的构建命令。
- **判定**：误用旧 flavor 命令（`assembleUniversal/assembleArmsolo/assembleX86solo`）。
- **产出**：只用 `assembleDebug/assembleRelease/bundleRelease`（项目无 flavor）。

## 3. PRoot 容器无法执行

- **操作**：检查 `targetSdk` 是否被改高。
- **判定**：`targetSdk` 被改高破坏 Android 10+ W^X 绕过（锁定 28）。
- **产出**：保持 `targetSdk = 28`，勿"顺手修复"。

## 4. 提交被 pre-commit / commit-msg 阻断

- **操作**：看阻断提示归类。
- **判定**：pre-commit 阻断多为新增/删除 feature 模块未同步 `docs/modules/`；commit-msg 阻断多为提交信息不合 Conventional Commits。
- **产出**：按提示新建/删除对应文档，或按 `type(scope): subject` 重写提交信息（`--no-verify` 仅紧急）。

## 5. APK 装不上 / 装后崩溃 / 版本号对不上

- **操作**：核对 ABI 与版本来源。
- **判定**：ABI 不符（通用包含 arm64-v8a + x86_64，其它 ABI 走无容器降级）；版本号对不上多为手改 `versionName`。
- **产出**：用双 ABI 通用包 / 靠 Git Tag 动态推导（代码中勿手写版本号）。
