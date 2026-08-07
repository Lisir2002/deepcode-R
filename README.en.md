<p align="center">
  <h1 align="center">R-DeepCode</h1>
  <p align="center">
    AI-powered coding assistant for Android · Built-in Linux terminal · AI Agent · MCP · Git integration
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
      <td align="center"><img src="docs/screenshots/home.png" alt="R-DeepCode home - AI chat interface with code generation and Markdown rendering" width="270"/></td>
      <td align="center"><img src="docs/screenshots/terminal.png" alt="R-DeepCode terminal - built-in Alpine Linux container, full command-line environment" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Home · AI Chat</td>
      <td align="center">Terminal · Alpine Linux</td>
    </tr>
  </table>
</p>

---

## Overview

R-DeepCode is an AI-powered coding assistant that runs natively on Android. It integrates large language models with a local Linux development environment. The built-in Alpine Linux container and terminal emulator let the AI directly read/write files, execute shell commands, and run build tools. It also supports remote SSH servers as the execution backend, turning your phone into a mobile workstation for remote projects.

## Features

- **AI Agent** — Supports Anthropic (Claude), OpenAI (GPT), Gemini, and other providers. Deeply interacts with the dev environment via a tool system (file operations, shell execution, terminal management, web search, etc.). Supports streaming output, context compression, and multi-session management
- **Built-in Terminal** — Based on Termux components + PRoot Alpine Linux container, providing a full Linux command-line environment with background persistence and multi-tab management
- **Remote SSH Mode** — Connect to a remote SSH server as the execution backend. Commands via exec channel, file I/O via SFTP, terminal via shell channel, with auto-reconnect and status indicator
- **MCP Protocol** — Model Context Protocol client, connecting to local (stdio) or remote (HTTP) MCP servers to dynamically extend tool capabilities
- **Git Integration** — Built-in visual Git operations (status/branches/commits/tags), with long-press action menus
- **Remote Sync** — SFTP / FTP workspace sync, with a built-in FTP server for desktop access
- **Markdown Rendering** — Real-time Markdown rendering in AI conversations, with code highlighting
- **Custom Prompts** — System prompts support user-defined overrides, preserved across app upgrades

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| Database | Room |
| Network | Retrofit + OkHttp |
| Async | Kotlin Coroutines / Flow |
| Terminal | Termux terminal-emulator + terminal-view |
| Container | PRoot + Alpine Linux rootfs |
| Remote SSH | SSHJ (exec channel + SFTP + shell channel) |
| Crypto | BouncyCastle (bcprov-jdk18on, sshj X25519 key exchange dependency) |
| FTP | Commons Net |

## Getting Started

### Prerequisites

- Android 8.0+ (API 26) arm64-v8a or x86_64 device
- JDK 17

### Build

```bash
# Single-flavor smoke build (recommended for daily dev, only builds universal debug APK)
./gradlew :app:assembleUniversalDebug

# Release (requires signing config; builds all three flavors)
./gradlew assembleRelease

# Build a single flavor
./gradlew assembleArmsoloRelease     # arm64-v8a only + arm image
./gradlew assembleX86soloRelease     # x86_64 only + x86 image
./gradlew assembleUniversalRelease   # arm64-v8a + x86_64, both images

# Release AAB
./gradlew bundleRelease
```

> Output path for all three flavors: `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

<details>
<summary>Release signing configuration</summary>

Add to `app/keystore.properties`:

```properties
storeFile=aicode.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

</details>

### Test

```bash
./gradlew :app:testUniversalDebugUnitTest    # Single-flavor unit tests (recommended)
./gradlew test                                # All-flavor unit tests
```

## Project Structure

```
app/src/main/java/com/aicode/
├── core/                # Core infrastructure (FileLogger, db/MigrationLoader, theme, common components)
├── feature/
│   ├── agent/           # AI Agent (prompts, MCP, tool registry, multi-provider adapters, slash commands)
│   ├── git/             # Git integration (status/branches/commits/tags)
│   ├── settings/        # App settings (providers, container, MCP, remote, logs, etc.)
│   ├── terminal/        # Terminal emulation & session management (local Termux + remote SSH)
│   └── workspace/       # Workspace & document management (local + remote SFTP/FTP)
├── AIEditorApp.kt       # Application entry point
└── MainActivity.kt      # Main Activity
```

## Known Limitations

- `targetSdk` is locked to 28 to bypass Android 10+ W^X policy, enabling PRoot execution.
- Release builds are split into three variants by CPU/container image:
  - `armsolo`: `arm64-v8a` only + arm image (recommended for physical devices)
  - `x86solo`: `x86_64` only + x86 image (emulators / Chromebooks)
  - `universal`: `arm64-v8a` + `x86_64`, both images (universal but larger)
  - Container images are selected by system ABI; installing the wrong architecture package will fail to run PRoot.

## Acknowledgements

- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference

## License

This project is licensed under [GPL-3.0](LICENSE).
