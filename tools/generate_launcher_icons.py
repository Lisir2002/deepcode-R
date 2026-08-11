#!/usr/bin/env python3
"""
R-DeepCode Launcher Icon 位图生成器 v3
  从 AI 生成的 1024×1024 主稿中色键抠除深蓝黑背景 → 透明前景
  再缩放生成 5 档 foreground / ic_launcher / ic_launcher_round WebP + PlayStore 512
"""
from PIL import Image, ImageDraw
import os

BG_COLOR = (0x0D, 0x11, 0x17)  # 深蓝黑（主稿背景色，要抠除）
BG_TOLERANCE = 20  # 色键容差：RGB 各通道差值 ≤ 此值视为背景

ROOT = "/workspace/deepcode-R"
RES_DIR = f"{ROOT}/app/src/main/res"
DESIGN_DIR = f"{ROOT}/docs/design/brand_icons"
MASTER_JPG = f"{DESIGN_DIR}/v3_ai_master_1024.jpg"

DENS = [
    ("mdpi",     48),
    ("hdpi",     72),
    ("xhdpi",    96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]
FG_SCALE = 2.25  # foreground = 108/48


def chroma_key_background(img, bg_color, tolerance=20):
    """
    色键抠除背景：将接近 bg_color 的像素 alpha 设为 0。
    返回 RGBA 图像，背景区域完全透明。
    """
    rgba = img.convert("RGBA")
    pixels = rgba.load()
    w, h = rgba.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if (abs(r - bg_color[0]) <= tolerance and
                abs(g - bg_color[1]) <= tolerance and
                abs(b - bg_color[2]) <= tolerance):
                pixels[x, y] = (0, 0, 0, 0)
            else:
                # 非背景像素保持不透明
                pixels[x, y] = (r, g, b, 255)
    return rgba


def feather_edges(img, radius=1):
    """对 alpha 通道做轻微模糊，消除色键硬边"""
    alpha = img.split()[3]
    alpha = alpha.filter(ImageFilter.GaussianBlur(radius=radius))
    img.putalpha(alpha)
    return img


def composite_on_bg(fg_img, size, bg_color=(0x0D, 0x11, 0x17, 0xFF)):
    """合成前景到纯色背景上"""
    bg = Image.new("RGBA", (size, size), bg_color)
    if fg_img.size != (size, size):
        fg_resized = fg_img.resize((size, size), Image.LANCZOS)
    else:
        fg_resized = fg_img
    bg.alpha_composite(fg_resized)
    return bg


def apply_round_mask(img, size):
    """圆形 alpha mask"""
    r = size // 2 - 1
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([1, 1, size - 2, size - 2], fill=255)
    result = img.copy()
    result.putalpha(mask)
    return result


def main():
    print("=== 1. 加载 AI 主稿 1024×1024 ===")
    master = Image.open(MASTER_JPG)
    print(f"  主稿: {master.size} {master.mode}")
    
    print("=== 2. 色键抠除深蓝黑背景 → 透明前景 1024×1024 ===")
    foreground_full = chroma_key_background(master, BG_COLOR, tolerance=BG_TOLERANCE)
    # 轻微羽化消除硬边
    from PIL import ImageFilter
    alpha = foreground_full.split()[3]
    alpha = alpha.filter(ImageFilter.GaussianBlur(radius=1))
    foreground_full.putalpha(alpha)
    # 保存透明前景底稿
    foreground_full.save(f"{DESIGN_DIR}/v3_foreground_transparent_1024.png", "PNG", optimize=True)
    print(f"  透明前景底稿保存: v3_foreground_transparent_1024.png")
    
    print("\n=== 3. 生成 5 档 mipmap 位图 ===")
    for suffix, px in DENS:
        fg_px = int(round(px * FG_SCALE))
        
        # foreground (透明背景)
        fg_resized = foreground_full.resize((fg_px, fg_px), Image.LANCZOS)
        fg_resized.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_foreground.webp", "WEBP", lossless=True, quality=100)
        
        # ic_launcher (合成深蓝黑背景)
        launcher = composite_on_bg(foreground_full, fg_px)
        launcher.convert("RGB").save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher.webp", "WEBP", quality=95, method=6)
        
        # ic_launcher_round (圆 mask)
        round_icon = composite_on_bg(foreground_full, fg_px)
        round_icon = apply_round_mask(round_icon, fg_px)
        round_icon.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_round.webp", "WEBP", lossless=True, quality=100)
        
        print(f"  ✅ {suffix:<8} @ fg={fg_px}px  (launcher {px}×{px})")
    
    print("\n=== 4. PlayStore 512×512 ===")
    playstore = composite_on_bg(foreground_full, 512)
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
    from PIL import ImageFilter
    main()
