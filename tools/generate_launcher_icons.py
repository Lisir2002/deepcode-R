#!/usr/bin/env python3
"""
R-DeepCode Launcher Icon 位图生成器 v4
  v4 主稿包含完整构图（深蓝黑背景 + 混合小元素 + 蓝紫渐变气泡 + 白色 </>）
  不需要色键抠除，直接缩放主稿生成全部位图。
  foreground = 主稿整体（adaptive-icon background 层用纯色兜底，foreground 覆盖全部）
"""
from PIL import Image, ImageDraw
import os

ROOT = "/workspace/deepcode-R"
RES_DIR = f"{ROOT}/app/src/main/res"
DESIGN_DIR = f"{ROOT}/docs/design/brand_icons"
MASTER_JPG = f"{DESIGN_DIR}/v4_ai_master_1024.jpg"

DENS = [
    ("mdpi",     48),
    ("hdpi",     72),
    ("xhdpi",    96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]
FG_SCALE = 2.25  # foreground = 108/48


def apply_round_mask(img, size):
    """圆形 alpha mask"""
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([1, 1, size - 2, size - 2], fill=255)
    result = img.copy()
    result.putalpha(mask)
    return result


def main():
    print("=== 1. 加载 v4 AI 主稿 ===")
    master = Image.open(MASTER_JPG).convert("RGBA")
    print(f"  主稿: {master.size} {master.mode}")

    # 保存主稿 PNG 副本（设计归档）
    master.save(f"{DESIGN_DIR}/v4_master_full.png", "PNG", optimize=True)

    print("\n=== 2. 生成 5 档 mipmap 位图 ===")
    for suffix, px in DENS:
        fg_px = int(round(px * FG_SCALE))

        # foreground (完整主稿缩放，保持不透明)
        fg_resized = master.resize((fg_px, fg_px), Image.LANCZOS)
        fg_resized.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_foreground.webp", "WEBP", lossless=True, quality=100)

        # ic_launcher (完整主稿缩放)
        launcher = master.resize((fg_px, fg_px), Image.LANCZOS)
        launcher.convert("RGB").save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher.webp", "WEBP", quality=95, method=6)

        # ic_launcher_round (圆 mask)
        round_icon = master.resize((fg_px, fg_px), Image.LANCZOS)
        round_icon = apply_round_mask(round_icon, fg_px)
        round_icon.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_round.webp", "WEBP", lossless=True, quality=100)

        print(f"  ✅ {suffix:<8} @ fg={fg_px}px  (launcher {px}×{px})")

    print("\n=== 3. PlayStore 512×512 ===")
    playstore = master.resize((512, 512), Image.LANCZOS)
    playstore.convert("RGB").save(f"{DESIGN_DIR}/playstore_512x512_launcher.png", "PNG", optimize=True)
    playstore.convert("RGB").save(f"{DESIGN_DIR}/playstore_512x512_launcher.webp", "WEBP", quality=95)
    print(f"  ✅ playstore_512x512_launcher.png/webp")

    print("\n=== 产物大小 ===")
    for suffix, _ in DENS:
        for fn in ("ic_launcher.webp", "ic_launcher_foreground.webp", "ic_launcher_round.webp"):
            p = f"{RES_DIR}/mipmap-{suffix}/{fn}"
            sz = os.path.getsize(p)
            print(f"  {sz:>7}B  mipmap-{suffix}/{fn}  ({sz/1024:.1f}KB)")


if __name__ == "__main__":
    main()
