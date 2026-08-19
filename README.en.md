<p align="center">
  <h1 align="center">R-CodeCore</h1>
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
  <a href="https://github.com/Lisir2002/deepcode-R/releases/latest"><img src="https://img.shields.io/github/v/release/Lisir2002/deepcode-R?display_name=tag&include_prereleases" alt="Latest Release" /></a>
  <a href="https://github.com/Lisir2002/deepcode-R/releases"><img src="https://img.shields.io/github/downloads/Lisir2002/deepcode-R/total" alt="Total Downloads" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="R-CodeCore home - AI chat interface with code generation and Markdown rendering" width="270"/></td>
      <td align="center"><img src="docs/screenshots/terminal.png" alt="R-CodeCore terminal - built-in Alpine Linux container, full command-line environment" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Home · AI Chat</td>
      <td align="center">Terminal · Alpine Linux</td>
    </tr>
  </table>
</p>

---

## Overview

R-CodeCore is an AI-powered coding assistant that runs natively on Android. It integrates large language models with a local Linux development environment. The built-in Alpine Linux container and terminal emulator let the AI directly read/write files, execute shell commands, and run build tools. It also supports remote SSH servers as the execution backend, turning your phone into a mobile workstation for remote projects.

## Features

- **AI Agent** — Supports Anthropic (Claude), OpenAI (GPT), Gemini, and other providers. Deeply interacts with the dev environment via 17 built-in tools (file read/write/edit, shell execution, terminal management, web search, MCP management, etc.). Supports streaming output, context compression, multi-session management, and PLAN/BUILD/AUTO execution modes
- **Permission & Safety** — Seven-layer permission evaluation engine: catastrophic command interception, PLAN-mode read-only constraints, shell static analysis, built-in read-only whitelist, user approval and rule memory
- **Checkpoints & Rollback** — Automatic file snapshots before modifications, with rollback to any checkpoint
- **Built-in Terminal** — Based on Termux components + PRoot Alpine Linux container, providing a full Linux command-line environment with background persistence, multi-tab management, and 6 built-in bundles (Python/Node/Git/Bash/rg/Network tools)
- **Remote SSH Mode** — Connect to a remote SSH server as the execution backend. Commands via exec channel, file I/O via exec + cat/base64, terminal via shell channel, with auto-reconnect and status indicator
- **MCP Protocol** — Model Context Protocol client, connecting to local (stdio) or remote (HTTP) MCP servers to dynamically extend tool capabilities
- **Git Integration** — Built-in visual Git operations (status/branches/commits/tags/graph), with unified credential management across three endpoints (UI Git / AI Bash / terminal git)
- **Remote Sync** — SFTP / FTP workspace sync, with a built-in FTP server for desktop access
- **Backup & Restore** — AES-256-GCM encrypted full data backup (sessions/config/credentials/workspace), with optional passphrase protection
- **Markdown Rendering** — Real-time Markdown rendering in AI conversations, with code highlighting
- **Custom Prompts** — System prompts support user-defined overrides, preserved across app upgrades

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.2.21 |
| Build | Android Gradle Plugin 8.9.3 + KSP |
| UI | Jetpack Compose (BOM 2025.12.01) + Material 3 |
| DI | Hilt 2.56.1 (Dagger) |
| Database | Room 2.7.1 (file-driven SQL migration system, Schema v31) |
| Network | Retrofit 2.11.0 + OkHttp 4.12.0 + Gson |
| Async | Kotlin Coroutines / Flow |
| Terminal | Termux terminal-emulator + terminal-view (JNI libtermux.so) |
| Container | PRoot + Alpine Linux 3.21 rootfs (arm64-v8a / x86_64) |
| Remote SSH | SSHJ 0.38.0 (exec channel + shell channel) |
| Crypto | BouncyCastle bcprov-jdk18on 1.75 + Android Keystore AES-GCM |
| FTP | Apache Commons Net 3.10.0 |
| Compression | Apache Commons Compress 1.26.2 (tar.gz / XZ) |
| Serialization | Gson + kotlinx.serialization |

## Getting Started

### Prerequisites

- **Physical device (officially supported)**: Android 8.0+ (API 26) **arm64-v8a** device (the mainstream ABI for current Android handsets)
- **Build environment**: JDK 17
- *Note: x86_64 emulators are not officially supported — design decision = "real devices only, no virtual machine considerations"*

### Build

```bash
# Daily dev smoke build (recommended, debug APK only; faster, no R8)
./gradlew :app:assembleDebug

# Release build (signing config required; auto-falls back to debug keystore when missing)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk (single arm64-v8a ABI)

# Release AAB
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

<details>
<summary>Release signing configuration</summary>

Add to `app/keystore.properties`:

```properties
storeFile=rcodecore.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

> `storeFile` path is customizable (filename not fixed). CI restores it from secrets to `app/rcodecore.jks`. When signing config is not present, release build auto-falls back to the debug keystore, so `assembleRelease` always produces an APK.

</details>

### Test

```bash
# Unit tests on release classpath (matches CI gate & user runtime; recommended)
./gradlew :app:testReleaseUnitTest
# Unit tests on debug classpath
./gradlew :app:testDebugUnitTest
```

### Cloud build (GitHub Actions release automation)

Releases are tag-driven: push a `v*` tag on a `main` commit (e.g. `git push origin v0.1.0-rc1` / `v0.1.0`) and [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml) takes over automatically → unit tests → `assembleRelease` → production signing → upload R8 mapping → create GitHub Release → attach `rcodecore-arm64-<tag>.apk` → write Run Summary. RC tags (containing `-rc`) are auto-marked as prerelease.

- **Production-signing prerequisite**: the repository `Settings → Secrets → Actions` must define 4 secrets — `AICODE_KEYSTORE_BASE64` / `AICODE_KEYSTORE_PASSWORD` / `AICODE_KEY_ALIAS` / `AICODE_KEY_PASSWORD`. Missing any one silently falls back to the debug keystore, and the artifact cannot be published.
- **Real-time monitoring & artifact verification** (mandatory after pushing a tag): poll `workflow_runs` + `jobs` via the GitHub API until `conclusion` settles → download the APK → `unzip -l` to confirm only `lib/arm64-v8a/*.so` → `keytool -printcert` to confirm it is not `CN=Android Debug` → `sha256sum` to record the fingerprint.
- **Full commands, the 6 CI jobs, and the signing-secrets verification API**: see [AGENTS.md § Cloud build & real-time monitoring automation](./AGENTS.md#云端构建与实时监控自动化).
- **Release page**: https://github.com/Lisir2002/deepcode-R/releases

## Project Structure

```
app/src/main/java/com/R/codecore/
├── core/                # Core infrastructure (FileLogger, AILogger, db/MigrationLoader, CredentialEncryptor, LineDiff, theme)
├── di/                  # Hilt DI (AgentModule, RepositoryModule, BackupModule)
├── feature/
│   ├── agent/           # AI Agent (MVI workflow, 17 tools, permission engine, MCP, skills, memory, checkpoints, provider adapters)
│   ├── credentials/     # Git credential management (3-endpoint IPC bridge, file sync, global dialog)
│   ├── git/             # Git visualization (status/branches/commits/tags/graph/diff)
│   ├── settings/        # App settings (AI provider management, container, MCP, remote, logs, etc.)
│   ├── terminal/        # Terminal emulation & session management (local PRoot + remote SSH, 6 built-in bundles)
│   ├── workspace/       # Workspace & document management (local + remote SFTP/FTP sync)
│   └── backup/          # AES-encrypted backup & restore
├── AIEditorApp.kt       # Application entry (BC registration, credential bridge, MCP, keepalive init)
└── MainActivity.kt      # Main Activity (NavHost + Drawer + global credential dialog)
```

> 📋 **AI collaboration guidelines**: See [AGENTS.md](./AGENTS.md) — asset sync discipline, Conventional Commits, branching workflow, release process (RC gating), and other must-read rules for development.

## Known Limitations

- `targetSdk` is locked to 28 to bypass Android 10+ W^X policy, enabling PRoot execution; trade-off: ineligible for Google Play (same as Termux).
- Release artifacts are **physical-device arm64-v8a single-ABI** APKs:
  - Supports all mainstream Android physical devices (Snapdragon/Dimensity/Kirin and other 64-bit ARM chipsets);
  - x86_64 emulators or x86 Chromebooks will fail at install time (missing `x86_64` native libs) — no runtime crashes, aligned with the "don't waste build resources on virtual machines" design decision.

## Acknowledgements

- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference

## License

This project is licensed under [GPL-3.0](LICENSE).
