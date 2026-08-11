#!/usr/bin/env python3
"""
R-DeepCode Launcher Icon 位图生成器
  技术选型：程序化 Pillow 绘制 银(#C0C0C0) 衬线 R 字母 + 纯黑(#0A0A0A) 背景
  完全不依赖 AI 位图，保证 100% 像素锐利、无压缩伪影、无透明噪点。
"""
from PIL import Image, ImageDraw, ImageFont
import os

BG = (0x0A, 0x0A, 0x0A, 0xFF)
FG_SILVER = (0xC0, 0xC0, 0xC0, 0xFF)

ROOT = "/workspace/deepcode-R"
RES_DIR = f"{ROOT}/app/src/main/res"
DESIGN_DIR = f"{ROOT}/docs/design/brand_icons"

DENS = [
    ("mdpi",     48),
    ("hdpi",     72),
    ("xhdpi",    96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]
FG_SCALE = 2.25  # foreground = 108/48, launcher合成用同fg画布


def draw_serif_R(img, cx, cy, w, h, color):
    d = ImageDraw.Draw(img)
    # 1) 衬线字体优先
    font_paths = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSerifBold.ttf",
    ]
    chosen = None
    for fp in font_paths:
        if os.path.isfile(fp):
            chosen = fp
            break

    if chosen:
        # 二分查找合适 size
        lo, hi = 8, 512
        best = None
        for _ in range(36):
            mid = (lo + hi) // 2
            ft = ImageFont.truetype(chosen, mid)
            L, T, R, B = d.textbbox((0, 0), "R", font=ft)
            bh = B - T
            if bh < h:
                lo = mid + 1
                best = (ft, L, T, R, B)
            elif bh > h * 1.03:
                hi = mid - 1
            else:
                best = (ft, L, T, R, B)
                break
        if best is None and lo > 8:
            ft = ImageFont.truetype(chosen, lo - 1)
            L, T, R, B = d.textbbox((0, 0), "R", font=ft)
            best = (ft, L, T, R, B)
        ft, L, T, R, B = best
        bw = R - L
        bh = B - T
        x0 = cx - bw / 2 - L
        y0 = cy - bh / 2 - T
        d.text((x0, y0), "R", fill=color, font=ft)
        return True

    # 2) fallback：极简几何粗体 R（保证无字体环境也出图）
    sw = max(1, int(w * 0.18))
    top = cy - h // 2
    bot = cy + h // 2
    left = cx - w // 2
    right = cx + w // 2
    d.rectangle([left, top, left + sw, bot], fill=color)
    d.rectangle([left + sw, top, right, top + sw], fill=color)
    d.rectangle([right - sw, top, right, cy], fill=color)
    d.rectangle([left + sw, cy - sw // 2, right - sw, cy + sw // 2], fill=color)
    leg_top = cy + sw // 2
    points = [
        (left + sw, leg_top),
        (left + sw + int(w * 0.30), leg_top),
        (right - int(w * 0.06), bot),
        (right - int(w * 0.06) - sw, bot),
    ]
    d.polygon(points, fill=color)
    return True


def make_foreground(fg_px):
    """透明底 + 银 R"""
    canvas = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
    # body 宽高按 fg_px * 0.62 高 * 0.42 宽
    body_h = int(fg_px * 0.62)
    body_w = int(fg_px * 0.42)
    cx = fg_px // 2
    cy = fg_px // 2
    draw_serif_R(canvas, cx, cy, body_w, body_h, FG_SILVER)
    return canvas


def composite(fg_img, out_webp, round_mask=False):
    size = fg_img.size[0]
    bg = Image.new("RGBA", (size, size), BG)
    bg.alpha_composite(fg_img)
    if round_mask:
        r = size // 2 - 1
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([1, 1, size - 2, size - 2], fill=255)
        bg.putalpha(mask)
        bg.save(out_webp, "WEBP", lossless=True, quality=100)
    else:
        bg.convert("RGB").save(out_webp, "WEBP", quality=95, method=6)


def make_playstore():
    size = 512
    fg = make_foreground(size)
    # 主稿 - 预览用 PNG
    bg = Image.new("RGBA", (size, size), BG)
    bg.alpha_composite(fg)
    bg.convert("RGB").save(f"{DESIGN_DIR}/playstore_512x512_launcher.png", "PNG", optimize=True)
    bg.convert("RGB").save(f"{DESIGN_DIR}/playstore_512x512_launcher.webp", "WEBP", quality=95)


def main():
    os.makedirs(DESIGN_DIR, exist_ok=True)
    for s, _ in DENS:
        os.makedirs(f"{RES_DIR}/mipmap-{s}", exist_ok=True)

    for suffix, px in DENS:
        fg_px = int(round(px * FG_SCALE))
        fg_img = make_foreground(fg_px)
        # foreground (透明)
        fg_img.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_foreground.webp", "WEBP", lossless=True, quality=100)
        fg_img.save(f"{DESIGN_DIR}/ic_launcher_foreground_{suffix}_{fg_px}x{fg_px}.png", "PNG", optimize=True)
        # launcher 合成
        composite(fg_img, f"{RES_DIR}/mipmap-{suffix}/ic_launcher.webp", round_mask=False)
        # round
        composite(fg_img, f"{RES_DIR}/mipmap-{suffix}/ic_launcher_round.webp", round_mask=True)
        print(f"  ✅ {suffix:<8} @ fg={fg_px}px  (base launcher {px}x{px})")

    make_playstore()
    print("\n=== 产物大小 ===")
    for suffix, _ in DENS:
        for fn in ("ic_launcher.webp", "ic_launcher_foreground.webp", "ic_launcher_round.webp"):
            p = f"{RES_DIR}/mipmap-{suffix}/{fn}"
            sz = os.path.getsize(p)
            print(f"  {sz:>7}B  mipmap-{suffix}/{fn}  ({sz/1024:.1f}KB)")


if __name__ == "__main__":
    main()
