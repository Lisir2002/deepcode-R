# 在 PRoot 容器中编译 Android 应用

本指南介绍如何在 ARM64 设备的 PRoot Linux 容器中，搭建 JDK + Android SDK 环境并从源码编译 Android 应用。本文以 Debian 12 (bookworm) aarch64 为例进行示范，实际也适用于 Ubuntu、Arch 等其他 Linux 发行版容器。

> **提示**：R-DeepCode 内置的 Alpine 容器因其 musl libc 与 PRoot 的特殊交互限制，编译 Android 应用时需要额外特殊处理（见末尾补充说明）。若要顺畅编译 Android 应用，推荐在 R-DeepCode 中导入自定义 Debian/Ubuntu 镜像使用。

---

## 环境基线

| 项 | 值 |
|---|---|
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64 |
| JDK | OpenJDK 17 |
| Gradle | 项目 wrapper 自带，无需系统安装 |
| SDK | Android 36 / build-tools 35.0.0 |
| aapt2 | ARM64 静态编译版 35.0.2（社区构建） |

---

## 第一步：安装基础依赖

```bash
apt update
apt install -y openjdk-17-jdk-headless curl wget unzip ripgrep
```

验证 JDK：

```bash
/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version
# 应输出 openjdk version "17..."
```

---

## 第二步：安装 Android SDK

### 2.1 下载命令行工具

从 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip 下载，放到工作区后：

```bash
mkdir -p ~/android/sdk
unzip /workspace/commandlinetools-linux-*.zip -d ~/android/sdk
mkdir -p ~/android/sdk/cmdline-tools/latest
mv ~/android/sdk/cmdline-tools/bin ~/android/sdk/cmdline-tools/lib ~/android/sdk/cmdline-tools/NOTICE.txt ~/android/sdk/cmdline-tools/source.properties ~/android/sdk/cmdline-tools/latest/
```

### 2.2 安装 SDK 组件

根据目标项目的 `compileSdk` 和 `buildToolsVersion` 选择对应组件：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/android/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

---

## 第三步：替换 ARM64 原生二进制

Google 官方的 aapt2、adb 等是 x86_64 编译，aarch64 上无法执行。用社区维护的 ARM64 静态编译版本替换。

### 3.1 下载

从 https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip 下载，放到工作区后：

```bash
unzip /workspace/android-sdk-tools-static-aarch64.zip -d ~/armtools35
```

### 3.2 替换 SDK 中的二进制

```bash
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
```

验证 aapt2 能跑：

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version
# 应输出版本号，不报错
```

---

## 第四步：配置

### 4.1 local.properties（项目根目录）

```bash
echo "sdk.dir=/root/android/sdk" > /workspace/local.properties
```

> 此文件通常已被 `.gitignore` 忽略，不会污染仓库。

### 4.2 全局 Gradle 配置（~/.gradle/gradle.properties）

aapt2 覆盖配置放在全局文件，不写入项目的 `gradle.properties`：

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

### 4.3 apt 源（可选）

容器默认可能连不上官方源，建议换国内镜像：

```
URIs: http://mirrors.ustc.edu.cn/debian
URIs: http://mirrors.ustc.edu.cn/debian-security
```

---

## 第五步：编译

```bash
cd /workspace
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
ANDROID_HOME=/root/android/sdk \
./gradlew assembleDebug
```

> 首次构建约需 10-15 分钟（下载依赖 + Kotlin 编译），后续增量构建约 1-2 分钟。
> Gradle daemon 在 PRoot 下正常工作，可加速增量构建。如遇 daemon 异常退出导致锁文件冲突，可加 `--no-daemon` 单次禁用。

成功后 APK 输出路径取决于项目配置，通常在：

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `JAVA_HOME is not set` | 未设环境变量 | 命令行加 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` |
| `SDK location not found` | 缺 `local.properties` | 创建 `local.properties` 写入 `sdk.dir=/root/android/sdk` |
| aapt2 `syntax error` | 用了 x86 版 aapt2 | 确认 `~/.gradle/gradle.properties` 里有 `aapt2FromMavenOverride` 指向 ARM 版 |
| `Failed to install build-tools` | AGP 联网验证失败 | 确认本地已装项目所需的 build-tools 版本 |
| apt 下载 403 | 镜像源临时不可用 | 换镜像源（清华 → 中科大） |
| Gradle 锁文件冲突 | 上次构建未正常退出 | 删除 `~/.gradle/caches/*.lock`，或加 `--no-daemon` |

---

## 补充：内置 Alpine 容器的 TemporaryFile.java 修复

如果使用内置 Alpine 容器编译 Android 应用，可能会遇到 `Failed to delete /tmp/tempdir_*` 错误。这是因为 AGP 的 `apkzlib` 中 `TemporaryFile` 使用 `File.delete()` 删除临时空目录，而在 PRoot 环境下该操作可能会失败。自定义 Debian 镜像不受此问题影响。

> **适用说明**：下述代码适用于 Gradle 8.11.1 / AGP 8.x 中包含的 `apkzlib`（对应包名 `com.android.tools.build.apkzlib.bytestorage.TemporaryFile`）。不同版本的 AGP/apkzlib 包路径与内部结构可能有所差异，建议根据具体使用的 jar 反编译出的源码结构进行调整。

解决方法是将 `TemporaryFile.java` 中的 `File.delete()` 修改为 `Files.delete()`（走 `unlinkat` 绕过底层删除失效）：

```java
package com.android.tools.build.apkzlib.bytestorage;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

public class TemporaryFile implements Closeable {
    private boolean deleted;
    private final File file;

    public TemporaryFile(File file) {
        this.deleted = false;
        this.file = file;
    }

    public File getFile() {
        Preconditions.checkState(!this.deleted, "File already deleted");
        return this.file;
    }

    @Override
    public void close() throws IOException {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        deleteFile(this.file);
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteFile(kid);
                }
            }
        }
        if (f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (NoSuchFileException e) {
                // 已存在则忽略
            } catch (IOException e) {
                if (!f.delete()) {
                    throw new IOException("Failed to delete '" + f.getAbsolutePath() + "'", e);
                }
            }
        }
    }
}
```

编译该源码并将其 `.class` 文件替换回 Gradle 缓存中的 `instrumented-apkzlib-*.jar` 即可。
