# 云端构建 · 发布运维手册

> 本文是 [AGENTS.md](../AGENTS.md)「发版流程」的深水区延伸（渐进披露：AGENTS.md 只保留摘要，本文为完整操作手册）。
> 面向：AI / 维护者操作 GitHub Actions 发版、实时监控、产物校验、签名 secrets 配置。

## 触发方式

- **自动触发**：`git push origin v0.1.0-rcN` / `git push origin v0.1.0`，CI 接收 `v*` tag push 事件后自动启动。
- **手动触发**（仅测试用）：GitHub Actions 页面 → `android-release.yml` → Run workflow（workflow_dispatch），versionName = `manual-<run_number>`，**不作为正式发版**。

## CI 全流程（单 job `build`，6 个逻辑阶段）

> workflow 实际是**单 job 多 step**结构（jobs.build），下述 6 个阶段是按职责划分的逻辑阶段，对应 step 序列。

1. **variables** → `Display release tag info` + `Verify versionCode monotonic`（versionCode 单调递增校验）+ `Determine release name`（手动触发用 `manual-<run_number>`）+ `Determine prerelease flag`（tag 含 `-rc/-dev/-beta/-alpha` 后缀自动标记 prerelease）
2. **build** → `:app:testReleaseUnitTest`（发版质量门禁）→ `:app:assembleRelease` → `Restore release keystore`（还原 `AICODE_KEYSTORE_BASE64` 到 `app/minime.jks`）→ `Generate keystore.properties`（用 4 个签名 secrets 生成临时 `keystore.properties`）→ **正式签名**构建 APK 到 `app/build/outputs/apk/release/app-release.apk` → `Rename APK` 重命名为 `dist/minime-<tag>.apk`（**双 ABI 通用包**，重命名同时做 ABI 校验：`lib/` 必须同时含 `arm64-v8a` 与 `x86_64`）
3. **upload-mapping** → `Upload R8 mapping`（`actions/upload-artifact@v4`，artifact 名 `r8-mapping-<tag>`，90 天保留，`if-no-files-found: ignore` 不阻塞）
4. **create-release** → `Generate changelog from git log` + `Create GitHub Release & Upload assets`（`softprops/action-gh-release@v2`，prerelease 取决于 tag 是否含预发布后缀）
5. **upload-apk** → 与 create-release 同 step 完成（`files: dist/minime-*.apk` 挂到 Release Assets）
6. **summary** → `Write download URLs to Run Summary`（写入 Tag / Prerelease / APK 文件名 / SHA256 / Release 页面 / mapping artifact 名到 `$GITHUB_STEP_SUMMARY`）

## 实时监控命令（GitHub API）

```bash
# 1. 查询最新 run 状态（tag 触发）
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/workflows/android-release.yml/runs?per_page=5" \
  | python3 -c "import sys,json;[print(r['id'],r['status'],r.get('conclusion','-'),r['head_branch']) for r in json.load(sys.stdin)['workflow_runs']]"

# 2. 查询指定 run 的每个 job 进度
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/runs/<run_id>/jobs" \
  | python3 -c "import sys,json;[print(j['name'],j['status'],j.get('conclusion','-')) for j in json.load(sys.stdin)['jobs']]"

# 3. 查询 Release 是否已创建 + APK asset
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/releases/tags/<tag>" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('tag_name'),d.get('prerelease'));[print(a['name'],a['size'],a['browser_download_url']) for a in d.get('assets',[])]"
```

## 产物校验清单（构建完成后必跑）

1. **下载 APK** → `curl -sL -u "<owner>:<token>" -o minime-<tag>.apk "<browser_download_url>"`
2. **ABI 校验** → `unzip -l <apk> | grep 'lib/.*\.so'` 必须**同时**含 `lib/arm64-v8a/*.so` 与 `lib/x86_64/*.so`（双 ABI 通用包）；容器资产应含 `assets/container/arm/alpine-rootfs.bin` 与 `assets/container/x86_64/alpine-rootfs-x86_64.bin`
3. **签名校验** → `keytool -printcert -jarfile <apk>` → Owner 必须为正式签名（非 `CN=Android Debug`）
4. **SHA256** → `sha256sum <apk>` 记录指纹
5. **Release 页面** → https://github.com/Lisir2002/deepcode-R/releases/tag/<tag>

## 签名策略（唯一官方密钥，已入库）

> **2026-09 变更**：正式签名密钥已按维护者决定**入库**，**不再依赖 CI Secrets**。
> 旧策略（从 `AICODE_KEYSTORE_*` 4 个 secrets 恢复 keystore / 生成 keystore.properties）已废弃并移除。

正式 release 一律使用**唯一官方 keystore**，本地与 CI 每一次构建加载同一把密钥，签名指纹恒定：

| 文件 | 路径 | 说明 |
|---|---|---|
| `minime.jks` | `app/minime.jks` | 唯一官方 keystore（已入库，随 checkout 自带） |
| `keystore.properties` | `app/keystore.properties` | storeFile / storePassword / keyAlias（minime）/ keyPassword（已入库） |

- **强制约束**：`app/build.gradle.kts` 的 `signingConfigs.release` 用 `require(keystorePropertiesFile.exists())` 强制——缺少 `app/keystore.properties` 即构建失败，**不存在「回退到 debug keystore 当正式签名」**。
- CI 工作流 `android-release.yml` 直接读取随仓库自带的 `app/minime.jks` + `app/keystore.properties` 签名，无需额外步骤。
- **⚠️ 安全提示（维护者已知情并授权入库）**：私钥随仓库公开，能读仓库者即可冒充该签名；若未来对公开分发敏感，应撤销本入库决定并改回 CI Secrets 持有密钥。
- **产物签名校验**：`keytool -printcert -jarfile <apk>` → Owner 应为 `CN=MiniMe-core, OU=Mobile, O=MiniMe, L=Beijing, ST=Beijing, C=CN`（密钥 SHA256 = `05:93:2E:DB:4D:94:BB:45:FB:81:9B:BA:D9:9F:70:01:0C:23:D8:82:CA:86:BF:D5:73:F0:0D:D7:03:80:24:03`），非 `CN=Android Debug`。
