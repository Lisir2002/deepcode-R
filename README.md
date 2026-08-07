<p align="center">
  <h1 align="center">R-DeepCode</h1>
  <p align="center">
    Android 端 AI 编程工具 · 内置 Linux 终端 · AI Agent · MCP 协议 · Git 集成
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose UI" />
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="Min SDK 26 (Android 8.0)" />
  <a href="https://github.com/jieapi/aicode/releases/latest"><img src="https://img.shields.io/github/v/release/jieapi/aicode?display_name=tag&include_prereleases" alt="Latest Release" /></a>
  <a href="https://github.com/jieapi/aicode/releases"><img src="https://img.shields.io/github/downloads/jieapi/aicode/total" alt="Total Downloads" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="R-DeepCode 主页 - AI 对话界面，支持代码生成与 Markdown 渲染" width="270"/></td>
      <td align="center"><img src="docs/screenshots/terminal.png" alt="R-DeepCode 终端 - 内置 Alpine Linux 容器，完整命令行环境" width="270"/></td>
    </tr>
    <tr>
      <td align="center">主页 · AI 对话</td>
      <td align="center">终端 · Alpine Linux</td>
    </tr>
  </table>
</p>

---

## 简介

R-DeepCode 是一款在 Android 手机上运行的 AI 编程工具，将大语言模型与本地 Linux 开发环境深度集成。它内置 Alpine Linux 容器和终端模拟器，让 AI 能直接读写文件、执行 Shell 命令、运行构建工具；同时支持远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

## 功能特性

- **AI Agent** — 支持 Anthropic（Claude）、OpenAI（GPT）、Gemini 等多家提供商，通过工具系统（文件操作、Shell 执行、终端管理、网页搜索等）与开发环境深度交互；支持流式输出、上下文压缩、多会话管理
- **内置终端** — 基于 Termux 组件 + PRoot Alpine Linux 容器，提供完整 Linux 命令行环境，支持后台常驻、多标签管理
- **远程 SSH 模式** — 连接远程 SSH 服务器作为执行后端，命令走 exec channel、文件读写走 SFTP、终端走 shell channel，支持自动重连与状态指示
- **MCP 协议** — Model Context Protocol 客户端，连接本地（stdio）或远程（HTTP）MCP 服务器动态扩展工具能力
- **Git 集成** — 内置可视化 Git 操作（状态/分支/提交/标签管理），支持长按操作菜单
- **远程同步** — 支持 SFTP / FTP 工作区同步，内置 FTP 服务器方便电脑端管理
- **Markdown 渲染** — AI 对话中实时渲染 Markdown，支持代码高亮
- **自定义提示词** — 系统提示词支持用户自定义覆盖，App 升级不丢失

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 依赖注入 | Hilt (Dagger) |
| 数据库 | Room |
| 网络 | Retrofit + OkHttp |
| 异步 | Kotlin Coroutines / Flow |
| 终端 | Termux terminal-emulator + terminal-view |
| 容器 | PRoot + Alpine Linux rootfs |
| 远程 SSH | SSHJ（exec channel + SFTP + shell channel） |
| 加密 | BouncyCastle（bcprov-jdk18on，sshj X25519 密钥交换依赖） |
| FTP | Commons Net |

## 快速开始

### 环境要求

- Android 8.0+（API 26）arm64-v8a 或 x86_64 设备
- JDK 17

### 构建

```bash
# 单 flavor 冒烟（日常开发推荐，只构 universal debug 一个 APK）
./gradlew :app:assembleUniversalDebug

# Release（需配置签名；构全部三个 flavor）
./gradlew assembleRelease

# 仅构单一 flavor
./gradlew assembleArmsoloRelease     # 仅 arm64-v8a + arm 镜像
./gradlew assembleX86soloRelease     # 仅 x86_64 + x86 镜像
./gradlew assembleUniversalRelease   # arm64-v8a + x86_64，含两套镜像

# Release AAB
./gradlew bundleRelease
```

> 三个 flavor 的产物路径：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

<details>
<summary>Release 签名配置</summary>

在 `app/keystore.properties` 中添加：

```properties
storeFile=aicode.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

</details>

### 测试

```bash
./gradlew :app:testUniversalDebugUnitTest    # 单 flavor 单元测试（日常推荐）
./gradlew test                                # 全 flavor 单元测试
```

## 项目结构

```
app/src/main/java/com/aicode/
├── core/                # 核心基础设施（FileLogger、db/MigrationLoader、主题、通用组件）
├── feature/
│   ├── agent/           # AI Agent（提示词、MCP、工具注册、多提供商适配、斜杠命令）
│   ├── git/             # Git 集成（状态/分支/提交/标签）
│   ├── settings/        # 应用设置（提供商、容器、MCP、远程、日志等）
│   ├── terminal/        # 终端模拟与会话管理（本地 Termux + 远程 SSH）
│   └── workspace/       # 工作区与文档管理（本地 + 远程 SFTP/FTP）
├── AIEditorApp.kt       # Application 入口
└── MainActivity.kt      # 主 Activity
```

## 已知限制

- `targetSdk` 锁定为 28 以绕过 Android 10+ W^X 策略，使 PRoot 可执行。
- Release 按 CPU/容器镜像拆三个 variant：
  - `armsolo`：仅 `arm64-v8a` + arm 镜像（真机首选）
  - `x86solo`：仅 `x86_64` + x86 镜像（模拟器 / Chromebook）
  - `universal`：`arm64-v8a` + `x86_64`，含两套镜像（通用但体积更大）
  - 容器镜像随系统 ABI 选择，错架构设备安装单架构包后无法运行 PRoot。

## 致谢

- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，本项目的核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供了终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考

## 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。
