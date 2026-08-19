# 云端构建 · 发布运维手册

> 本文是 [AGENTS.md](../AGENTS.md)「发版流程」的深水区延伸（渐进披露：AGENTS.md 只保留摘要，本文为完整操作手册）。
> 面向：AI / 维护者操作 GitHub Actions 发版、实时监控、产物校验、签名 secrets 配置。

## 触发方式

- **自动触发**：`git push origin v0.1.0-rcN` / `git push origin v0.1.0`，CI 接收 `v*` tag push 事件后自动启动。
- **手动触发**（仅测试用）：GitHub Actions 页面 → `android-release.yml` → Run workflow（workflow_dispatch），versionName = `manual-<run_number>`，**不作为正式发版**。

## CI 全流程（单 job `build`，6 个逻辑阶段）

> workflow 实际是**单 job 多 step**结构（jobs.build），下述 6 个阶段是按职责划分的逻辑阶段，对应 step 序列。

1. **variables** → `Display release tag info` + `Verify versionCode monotonic`（versionCode 单调递增校验）+ `Determine release name`（手动触发用 `manual-<run_number>`）+ `Determine prerelease flag`（tag 含 `-rc/-dev/-beta/-alpha` 后缀自动标记 prerelease）
2. **build** → `:app:testReleaseUnitTest`（发版质量门禁）→ `:app:assembleRelease` → `Restore release keystore`（还原 `AICODE_KEYSTORE_BASE64` 到 `app/rcodecore.jks`）→ `Generate keystore.properties`（用 4 个签名 secrets 生成临时 `keystore.properties`）→ **正式签名**构建 APK 到 `app/build/outputs/apk/release/app-release.apk` → `Rename APK` 重命名为 `dist/rcodecore-<tag>.apk`（**双 ABI 通用包**，重命名同时做 ABI 校验：`lib/` 必须同时含 `arm64-v8a` 与 `x86_64`）
3. **upload-mapping** → `Upload R8 mapping`（`actions/upload-artifact@v4`，artifact 名 `r8-mapping-<tag>`，90 天保留，`if-no-files-found: ignore` 不阻塞）
4. **create-release** → `Generate changelog from git log` + `Create GitHub Release & Upload assets`（`softprops/action-gh-release@v2`，prerelease 取决于 tag 是否含预发布后缀）
5. **upload-apk** → 与 create-release 同 step 完成（`files: dist/rcodecore-*.apk` 挂到 Release Assets）
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

1. **下载 APK** → `curl -sL -u "<owner>:<token>" -o rcodecore-<tag>.apk "<browser_download_url>"`
2. **ABI 校验** → `unzip -l <apk> | grep 'lib/.*\.so'` 必须**同时**含 `lib/arm64-v8a/*.so` 与 `lib/x86_64/*.so`（双 ABI 通用包）；容器资产应含 `assets/container/arm/alpine-rootfs.bin` 与 `assets/container/x86_64/alpine-rootfs-x86_64.bin`
3. **签名校验** → `keytool -printcert -jarfile <apk>` → Owner 必须为正式签名（非 `CN=Android Debug`）
4. **SHA256** → `sha256sum <apk>` 记录指纹
5. **Release 页面** → https://github.com/Lisir2002/deepcode-R/releases/tag/<tag>

## 签名 Secrets 前置条件（构建正式签名 APK 必须配置）

仓库 `Settings → Secrets and variables → Actions` 必须配置以下 4 个 secrets：

| Secret 名称 | 取值 |
|---|---|
| `AICODE_KEYSTORE_BASE64` | `app/rcodecore.jks` 文件的 base64 编码 |
| `AICODE_KEYSTORE_PASSWORD` | keystore 的 storePassword |
| `AICODE_KEY_ALIAS` | 签名 key 的 keyAlias |
| `AICODE_KEY_PASSWORD` | key 的 keyPassword |

**验证 secrets 是否存在**：
```bash
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/secrets?per_page=30" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('secrets 总数:',d.get('total_count',0));[print(' -',s['name']) for s in d.get('secrets',[])]"
```

> **⚠️ 若 secrets 总数 = 0 或缺少任一**：CI 构建的 APK 会**回退到项目级固定 debug keystore 签名**（`CN=Android Debug, O=Android, C=US`，alias=`androiddebugkey`，密码均为 `android`，APK 签名证书 SHA256 固定 = `7A:D5:EA:0E:3F:A9:6F:10:26:29:21:0C:9C:DB:AA:81:E3:CE:D4:9B:32:20:A5:21:7B:64:EC:1A:95:D2:FA:C8`）。
> 该方案**保证所有未配置正式签名的 Tag 构建输出同一份证书指纹的 APK**：RC24 / RC25 / … / RC∞ 之间覆盖安装不会再报「软件包与现有软件包冲突」。
> 但它**仍不可上架**（证书 Owner 必须为开发者主体，而非 Android Debug），若需上架请配置上面 4 个 secrets。
