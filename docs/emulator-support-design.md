# 虚拟环境（模拟器/虚拟机）支持 · 设计文档 v1.0（草案）

> 状态：📝 草案，待评审
> 目标：让 R-CodeCore 从「真机专用」平滑演进到「真机 + 虚拟环境（模拟器 / 虚拟机）均可用」
> 对应代码库：[deepcode-R](/workspace/deepcode-R)
> 相关入口：`AGENTS.md` / `docs/modules/`（模块文档）/ `docs/ci-release.md`（发版运维）

---

## 1. 背景与目标

当前产品定位为「真机 arm64-v8a 单架构」：设计决策为「只适配真机、不考虑虚拟机」。但该定位带来几个现实痛点：

- 无真机的开发者/用户无法在模拟器上体验或调试；
- CI 只能跑纯 JVM 单测，无法做端到端冒烟（容器启动、终端、AI 工具链路）；
- x86_64 模拟器安装 arm64-only APK 直接在安装阶段失败，体验割裂。

本设计的目标是**分层可用**：不追求「一步到位全功能」，而是让 App 在虚拟环境里**至少能装、AI 核心可用**，按需升级到**完整容器/终端能力**，并保持真机体验零回退。

---

## 2. 现状盘点：真机绑定的三个决策点（代码证据）

经代码核实，「真机专用」由三个相互独立的技术决策构成：

| # | 绑定点 | 代码位置 | 影响 |
|---|---|---|---|
| P1 | **ABI 单架构打包** | [app/build.gradle.kts](file:///workspace/deepcode-R/app/build.gradle.kts#L141) `ndk { abiFilters += "arm64-v8a" }` | x86_64 宿主模拟器**安装即失败**（缺 x86_64 so） |
| P2 | **targetSdk 锁定 28** | [app/build.gradle.kts](file:///workspace/deepcode-R/app/build.gradle.kts#L127-L129) | PRoot 需在 app 可写目录执行二进制（Android 10+ W^X）。**在模拟器上同样成立，不是额外负担** |
| P3 | **容器 rootfs 架构资产** | [ContainerInstaller.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerInstaller.kt#L19-L25) 已留双容器架构设计；`_x86Assets` 已按旧决策删除 | 容器/终端运行期能力 |

**关键利好**：执行后端**已经是可插拔的**——

- [DelegatingFileAccess.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/workspace/domain/DelegatingFileAccess.kt) 按执行模式自动分发本地/远程文件访问；
- 终端有本地 PRoot 与远程 SSH 双后端；
- **远程 SSH 执行模式完全不依赖容器**，是「模拟器上 AI 核心可用」的天然地基。

---

## 3. 核心认知：把「模拟器支持」拆成三个正交问题

1. **能不能装**（打包 ABI）→ P1
2. **AI 核心能不能跑**（对话/文件/工具，**大部分不依赖 PRoot**）→ 几乎无墙
3. **完整容器/终端能不能跑**（最重，依赖 PRoot + rootfs 架构）→ P1 + P3

补充事实：**Android Emulator 官方提供 arm64-v8a 系统镜像**（在 Intel/AMD 主机上由 QEMU 转译运行），**该镜像可直接安装当前 arm64-only APK**。因此「支持虚拟环境」的第一优先路径可能无需改 ABI。

---

## 4. 目标架构：运行环境抽象层

新增一个「运行环境」概念，作为所有真机绑定的唯一适配入口：

```
┌─────────────────────────────────────────────┐
│  ExecutionEnvironment（启动时探测，单例）        │
│  REAL_DEVICE_ARM64 / EMULATOR_ARM64 /          │
│  EMULATOR_X86_64 / VM_OTHER                    │
└──────────────┬────────────────────────────────┘
               │ 决策分发
   ┌───────────┼────────────────┐
   ▼           ▼                ▼
 打包/安装   容器 rootfs      执行后端/降级
 (P1)        (P3)            (复用现有抽象)
```

- **探测来源**：`Build.FINGERPRINT`（含 `generic`/`emulator`）、`Build.PRODUCT`、`ro.kernel.qemu`、`Build.SUPPORTED_ABIS`。
- **一处探测、处处适配**：所有「真机绑定」改从环境枚举取值，禁止散落的硬编码判断。

---

## 5. 实施方向与分层

| 方向 | 内容 | 侵入度 | 阶段 |
|---|---|---|---|
| **A** | 明确支持 arm64 模拟器镜像（≈ 零代码改动 + 文档/验收） | 极低 | P0 |
| **B** | 环境探测抽象 + 无容器分层降级（AI 核心在 x86_64 模拟器可用） | 中 | P1（核心架构） |
| **C** | 双 ABI 打包 + x86_64 容器资产（x86_64 模拟器获得完整终端/容器） | 高 | P2 |
| **D** | GitHub Actions 模拟器端到端冒烟门禁 | 中 | P3（配套固化） |

建议演进顺序 **A → B → C → D**，每步独立交付、独立回退。

---

## 6. 详细设计

### 6.1 环境探测（方向 B 起点）

```kotlin
enum class ExecutionEnvironment {
    REAL_DEVICE_ARM64,
    EMULATOR_ARM64,
    EMULATOR_X86_64,
    VM_OTHER;
}

object EnvironmentDetector {
    private val isQemu: Boolean
        get() = SystemProperties.get("ro.kernel.qemu", "0") == "1"

    fun detect(): ExecutionEnvironment {
        val abis = Build.SUPPORTED_ABIS
        val isEmu = isQemu || Build.FINGERPRINT.contains("generic", ignoreCase = true)
            || Build.PRODUCT?.contains("emulator", ignoreCase = true) == true
        return when {
            !isEmu -> REAL_DEVICE_ARM64
            abis.contains("arm64-v8a") -> EMULATOR_ARM64
            abis.contains("x86_64") -> EMULATOR_X86_64
            else -> VM_OTHER
        }
    }
}
```

> 注：探测能力仅用于**适配与降级**，不用于任何安全判断；虚拟环境不应被信任为安全边界。

### 6.2 环境适配点清单（改哪里）

| 适配点 | 现状 | 改后 |
|---|---|---|
| 打包 ABI（P1） | `abiFilters = ["arm64-v8a"]` | 由环境策略决定（见 6.3） |
| 容器 rootfs 架构（P3） | `ContainerInstaller.ASSET_DIR` 固定 `container/arm` | 按环境选 `arm` / `x86_64`（复用已有双架构安装逻辑骨架） |
| Bundle 安装 | Alpine v3.21 arm64 apk | x86_64 环境切 x86_64 仓库（`TerminalBundles` 加架构维度） |
| 执行后端 | `DelegatingFileAccess` 按 ExecutionMode 分发 | 增加「环境不支持容器时」的降级路由 |
| 帮助文档/提示词 | 真机口径 | 虚拟环境口径文案（走 `assets/docs` + `prompts` 同步纪律） |

### 6.3 打包与 ABI 策略（方向 C）

- **方案 C1（发布即双 ABI）**：`abiFilters = ["arm64-v8a", "x86_64"]`。收益：x86_64 模拟器直接可装；代价：APK +3~5MB、CI 构建翻倍、双架构产物校验（沿用 [docs/ci-release.md](file:///workspace/deepcode-R/docs/ci-release.md) 校验清单扩展）。
- **方案 C2（发布单 arm64，CI/开发另出 x86_64 debug）**：保持发布体积，仅开发者/CI 场景获得 x86_64 产物。
- **推荐**：先 C2 保障真机体验零回退，验证充分后再评估 C1。

### 6.4 容器架构选择与 x86_64 转译（方向 C 核心）

`ContainerInstaller` 注释已表明曾设计双容器架构：

- **arm64 环境**：aarch64 rootfs 原生执行（现状）；
- **x86_64 环境**：x86_64 rootfs，经 proot `-q` 注入静态 `qemu-*-static` 转译（宿主侧 proot/loader 仍 arm64 版）；
- **无容器降级**：跳过容器安装，命令执行降级/提示。

**反直觉净收益**：Android SDK Build-Tools 是 x86_64 ELF，真机上依赖 `QEMU_X86_TRANSLATOR` bundle 转译；在 x86_64 模拟器上**原生可执行**——「容器内构建 Android APK」场景在模拟器反而更顺畅。

### 6.5 无容器降级模式（方向 B 核心）

当探测到虚拟环境且容器不可用时：

- **可用**：AI 对话、本地文件读写（`LocalFileAccess` 不依赖容器）、Git 可视化、远程 SSH 模式、备份恢复；
- **降级提示**：容器内执行命令 / 终端等依赖 PRoot 的能力，提示「当前环境不支持容器，请使用远程 SSH 模式或真机」；
- 复用现有 [DelegatingFileAccess.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/workspace/domain/DelegatingFileAccess.kt) 分发骨架，新增一条「无容器」路由即可。

### 6.6 CI 门禁（方向 D）

在 `.github/workflows/` 增加可选 job：启动模拟器（arm64 镜像或 x86_64 镜像）→ 安装产物 → 冒烟（启动 + AI 对话链路 + 终端/容器按环境能力）。

---

## 7. 里程碑与验收标准

| 里程碑 | 交付 | 验收 |
|---|---|---|
| M0（方向 A） | 文档声明 + arm64 模拟器手动验证 | arm64 系统镜像上：安装成功、AI 对话、终端、容器均可用 |
| M1（方向 B） | `EnvironmentDetector` + 降级路由 | x86_64 模拟器上：能装（若有 x86_64 产物）、AI 核心可用、容器能力正确降级 |
| M2（方向 C） | 双 ABI 打包 + x86_64 rootfs | x86_64 模拟器上：完整终端/容器可用，产物校验通过 |
| M3（方向 D） | CI 模拟器冒烟 job | 每次合并自动验证虚拟环境可用性 |

---

## 8. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 双 ABI 体积增长 | +3~5MB | 先 C2 后评估 C1 |
| CI 构建时间翻倍 | 迭代变慢 | 双架构构建并行、缓存隔离 |
| arm64 镜像在 Intel 主机转译慢 | 体验下降 | 文档明确性能预期；x86_64 原生执行 |
| 模拟器 ROM 差异（W^X 等） | 偶发启动/容器问题 | targetSdk=28 保持；异常路径走降级 |
| 虚拟环境安全边界 | 不应被信任 | 探测仅用于适配，不用于授权 |

---

## 9. 决策记录

| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-19 | 采用「分层可用」策略，A→B→C→D 演进 | 每步独立交付、独立回退，优先保障真机零回退 |
| 2026-08-19 | 先出本设计文档，评审后再实施 | 待评审 |
