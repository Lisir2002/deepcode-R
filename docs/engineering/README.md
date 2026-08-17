# docs/engineering — R-CodeCore 工程经验索引

> 定位：只收录**在本项目里踩过坑、付过真实代价、有具体修复锚点**的经验手册。
> 不收录任何"工程最佳实践"类的泛泛而谈。

## 主手册（必读）

| 文档 | 主题 | 核心事件 |
|---|---|---|
| **[startup-crash-lessons-RC61.md](./startup-crash-lessons-RC61.md)** | Android 启动稳定性 / 升级兼容性 / CI 守卫 —— 9 大铁律 + 反模式红黑榜 + 3 套 SOP + 决策表 + 发布 Checklist + 复盘模板 | RC60 → RC61 「升级用户 1-2 秒闪退、重装就好、无日志」事件 |

> 每次发 RC / 正式版本前，发布负责人必须通读 [startup-crash-lessons-RC61.md §6 Checklist](./startup-crash-lessons-RC61.md#6-rc--正式发布前-checklist打印--手动打勾) 并逐项打勾。

## 事件复盘归档（按模板 §7 填写，每起一个文件）

复盘写入 [`./incidents/`](./incidents/) 目录。

| 文件 | 版本 | 类型 | 状态 |
|---|---|---|---|
| — | — | — | （RC61 事件复盘待下一轮填入） |

## 外部经验引用（通过 ExperienceRecall 沉淀）

| Experience ID | 主题 | 在主手册中的位置 |
|---|---|---|
| 479976 | Android ABI / 签名 / 构建产物 / mkversion / 崩溃证据链 | 铁律 9 + 反模式 3.7/3.8/3.9 + SOP 4.1 |
| 291148 | 补丁最小化 / 无崩溃栈不大改 / Windows 解码器线程池头文件反模式 | 铁律 8 + 反模式 3.6 |
| 1498720 | 真机闪退 vs 模拟器干扰 / AndroidManifest 自写解析器反模式 / 固定崩溃采集流程 | SOP 4.1 + 铁律 9 + 反模式 3.7 |

## 维护规则

1. **遇到新的闪退/升级兼容性/CI 故障** → 先查主手册对应条款；若手册里没覆盖 → 解决后**把新条款追加进主手册对应章节**。
2. **每次事件结束（CI 成功 + Release 成功 + 用户验证完成）** → 用 [startup-crash-lessons-RC61.md §7 复盘模板](./startup-crash-lessons-RC61.md#7-事后复盘标准模板) 新建 `incidents/YYYYMMDD-<slug>.md`。
3. **Checklist 是强制项**：任何 tag push 前应至少在 commit message 附一句 "Checklist: §6 全 ✓"。
