#!/usr/bin/env python3
"""Build Android launcher icons from master artwork for R-CodeCore.

主图为用户提供的设计稿（2048x2048 JPEG，白底）。

Generates for each density:
  ic_launcher.webp             - legacy square icon（整图，方形）
  ic_launcher_round.webp       - legacy round mask（圆形蒙版）
  ic_launcher_foreground.webp  - adaptive icon foreground
    （整图缩放到 66dp 安全区居中，透明留边——logo 100% 完整显示，
      与白色 @color/ic_launcher_background 无缝融合）

Also exports:
  artwork/rcodecore-launcher-playstore-512.png (Play Store listing, 512x512)
"""
from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "artwork" / "rcodecore-launcher-master.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
OUT_PLAY = ROOT / "artwork" / "rcodecore-launcher-playstore-512.png"

# Android launcher icon legacy mipmap 尺寸
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# adaptive icon 画布 108dp，可见安全区 66dp → 66/108 ≈ 0.611
SAFE_ZONE_RATIO = 66.0 / 108.0


def square_crop_cover(im: Image.Image) -> Image.Image:
    """Center-crop to square with COVER strategy."""
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def make_foreground(master_sq: Image.Image, size: int) -> Image.Image:
    """adaptive foreground：整图缩放进 66dp 安全区居中，四周透明。

    保证用户设计的 logo 在任何 mask（圆形/圆角/squircle）下 100% 完整可见。
    """
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * SAFE_ZONE_RATIO)
    fg = master_sq.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    offset = (size - inner) // 2
    canvas.paste(fg, (offset, offset))
    return canvas


def mask_round(im: Image.Image) -> Image.Image:
    """圆形蒙版（ic_launcher_round 用）。"""
    im = im.convert("RGBA")
    side = im.size[0]
    mask = Image.new("L", (side, side), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, side - 1, side - 1), fill=255)
    out = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def save_webp(im: Image.Image, path: Path, quality: int = 95) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "WEBP", quality=quality, method=6)


def save_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)


def main() -> None:
    assert SRC.exists(), f"Missing master: {SRC}"
    master = Image.open(SRC)
    master_sq = square_crop_cover(master)

    # Play Store 512
    ps = master_sq.resize((512, 512), Image.LANCZOS)
    save_png(ps.convert("RGB"), OUT_PLAY)
    print(f"wrote {OUT_PLAY}")

    for density, size in DENSITIES.items():
        out_dir = RES / density

        # legacy 方形：整图
        base = master_sq.resize((size, size), Image.LANCZOS).convert("RGB")
        save_webp(base, out_dir / "ic_launcher.webp")

        # legacy 圆形：整图圆形蒙版
        round_im = mask_round(master_sq.resize((size, size), Image.LANCZOS))
        save_webp(round_im, out_dir / "ic_launcher_round.webp")

        # adaptive 前景：安全区居中整图
        fg = make_foreground(master_sq, size)
        save_webp(fg, out_dir / "ic_launcher_foreground.webp")
        print(f"{density}: {size}x{size} ok")


if __name__ == "__main__":
    main()
