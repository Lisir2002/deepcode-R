# 自定义容器镜像

DeepCore-Code 默认使用内置的 Alpine rootfs 通过 PRoot 运行 Linux 容器。如果需要不同的发行版或预装特定工具链的环境，可以导入自定义 rootfs 镜像。

---

## 镜像格式要求

- 支持的压缩格式：`.tar.gz`、`.tgz`、`.tar.xz`、`.txz`
- 内容必须是 Linux rootfs（完整的文件系统根目录），包含 `/bin/sh` 或其他 shell
- 架构需与设备匹配：ARM64 设备用 `aarch64` 镜像，x86 设备用 `x86_64` 镜像

---

## 哪里可以下载镜像

### Alpine

Alpine 官方提供各架构的 minirootfs，体积小、启动快：

- 下载地址：https://alpine.linuxhub.cn/alpine/edge/releases/ （选 `aarch64` 下的 `alpine-minirootfs-*-aarch64.tar.gz`）
- 官方源：https://dl-cdn.alpinelinux.org/alpine/edge/releases/

### Debian

- Docker Hub 也可提取：从 `arm64v8/debian` 等官方镜像导出 rootfs

### Ubuntu

- 下载地址：https://cdimage.ubuntu.com/ubuntu-base/releases/ （选 `ubuntu-base-*-base-arm64.tar.gz`）

### 从 Docker 镜像提取 rootfs

如果有 Docker 环境，可以从任意 Docker 镜像导出 rootfs：

```bash
# 拉取镜像
docker pull debian:bookworm

# 创建临时容器并导出 rootfs
docker create --name temp-rootfs debian:bookworm
docker export temp-rootfs | gzip > debian-rootfs.tar.gz
docker rm temp-rootfs
```

### 自己制作 rootfs

使用 `debootstrap`（Debian/Ubuntu）或 `alpine-make-rootfs`（Alpine）可以在已有 Linux 环境中制作 rootfs：

```bash
# Debian 示例
debootstrap --arch=arm64 --foreign bookworm rootfs-dir http://deb.debian.org/debian
cd rootfs-dir && tar czf ../debian-rootfs.tar.gz .
```

---

## 在 DeepCore-Code 中导入自定义镜像

1. 进入「设置」->「容器镜像」
2. 点击右上角「+」号，选择「本地镜像」
3. 填写以下配置：
   - **名称**：镜像配置的别名，用于在列表中识别
   - **shell 路径**：容器内的 shell 可执行文件路径，如 `/bin/sh`、`/bin/bash`
   - **镜像文件**：选择下载好的 tar.gz / tar.xz 文件
   - **额外绑定**（可选）：把宿主目录挂载到容器内，空格分隔，格式 `宿主路径:容器路径`，如 `/sdcard:/mnt/sdcard`
   - **额外 proot 参数**（可选）：原样追加到 PRoot 启动参数
4. 保存后，在镜像列表中选中该 profile 即切换为自定义容器

---

## 注意事项

- 自定义镜像**不会自动安装工具**：内置 Alpine 会在首次启动时自动安装 python3、git 等基础工具，自定义镜像不会执行此步骤，所需工具需在镜像中预装或手动进入容器安装
- 自定义镜像**不会配置镜像源**：如果需要包管理器，确保镜像内的源配置正确
- 首次使用自定义镜像时，DeepCore-Code 会解压 rootfs 到 App 私有目录，耗时取决于镜像大小
- 删除自定义镜像会一并清理其解压的 rootfs 目录，释放存储空间
- 内置 Alpine 镜像不可删除，随时可以切回
