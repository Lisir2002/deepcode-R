#!/usr/bin/env python3
"""Build Android launcher icons from master artwork for R-CodeCore.

主图为用户提供的设计稿（2048x2048，白底圆角卡片）。用户反馈：
"卡片外层一圈大白边"在手机图标里显小。处理：
  1) 自动检测圆角方块卡片的边界（亮度扫描），去除 ~233px 外层大白边，
     仅保留卡片本体 + 30px 圆角余量；
  2) 自适应前景：裁剪后整图填满 108dp 画布（不再做安全区缩放），
     让 R 图形在手机启动器里占满，避免"画中画"；
  3) Legacy 方形/圆形：同样用裁剪后整图 → 圆形蒙版做 round。

白底（R,G,B ≥ 250）与 Android adaptive 背景色 #FFFFFF 视觉一致，
因此自适应图标的"前景内白底"在手机上看起来 = 纯白背景，无违和。

Densities:
  mdpi=48 / hdpi=72 / xhdpi=96 / xxhdpi=144 / xxxhdpi=192
"""
from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "artwork" / "rcodecore-launcher-master.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
OUT_PLAY = ROOT / "artwork" / "rcodecore-launcher-playstore-512.png"
OUT_CROPPED = ROOT / "artwork" / "rcodecore-launcher-cropped.png"   # 供人工审查

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

WHITE_THRESHOLD = 245  # lum (0..255)；高于它的像素视为"外层大白边"
CARD_PADDING_KEEP = 30  # 裁剪后为圆角/阴影保留 30px 安全余量


def _lum(pixel: tuple[int, int, int]) -> float:
    return 0.299 * pixel[0] + 0.587 * pixel[1] + 0.114 * pixel[2]


def find_card_bbox(im: Image.Image) -> tuple[int, int, int, int]:
    """亮度扫描：找到内部圆角卡片的外边界（L,R,T,B 各扫中心十字三行，取第一个亮度<阈值位置）。"""
    w, h = im.size
    cx, cy = w // 2, h // 2

    def scan_left() -> int:
        hits = []
        for y in (cy - 10, cy, cy + 10):
            for x in range(w):
                if _lum(im.getpixel((x, y))) < WHITE_THRESHOLD:
                    hits.append(x); break
        return min(hits)
    def scan_right() -> int:
        hits = []
        for y in (cy - 10, cy, cy + 10):
            for x in range(w - 1, -1, -1):
                if _lum(im.getpixel((x, y))) < WHITE_THRESHOLD:
                    hits.append(x); break
        return max(hits)
    def scan_top() -> int:
        hits = []
        for x in (cx - 10, cx, cx + 10):
            for y in range(h):
                if _lum(im.getpixel((x, y))) < WHITE_THRESHOLD:
                    hits.append(y); break
        return min(hits)
    def scan_bottom() -> int:
        hits = []
        for x in (cx - 10, cx, cx + 10):
            for y in range(h - 1, -1, -1):
                if _lum(im.getpixel((x, y))) < WHITE_THRESHOLD:
                    hits.append(y); break
        return max(hits)

    L, R, T, B = scan_left(), scan_right(), scan_top(), scan_bottom()
    # 外扩 padding 用于保留圆角与阴影
    L = max(0, L - CARD_PADDING_KEEP)
    R = min(w - 1, R + CARD_PADDING_KEEP)
    T = max(0, T - CARD_PADDING_KEEP)
    B = min(h - 1, B + CARD_PADDING_KEEP)
    return (L, T, R + 1, B + 1)   # PIL crop 是左闭右开


def crop_card_to_square(im: Image.Image) -> Image.Image:
    """找到圆角卡片区域 → 裁掉外层大白边 → 正方形居中 + COVER 化。"""
    L, T, R, B = find_card_bbox(im)
    cropped = im.crop((L, T, R, B))
    cw, ch = cropped.size
    side = max(cw, ch)
    # 正方形化：短边两侧补等距白底（实际卡片几乎正方形，补量极小）
    canvas = Image.new("RGB", (side, side), (255, 255, 255))
    off_x = (side - cw) // 2
    off_y = (side - ch) // 2
    canvas.paste(cropped, (off_x, off_y))
    return canvas


def mask_round(im: Image.Image) -> Image.Image:
    im = im.convert("RGBA")
    side = im.size[0]
    mask = Image.new("L", (side, side), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, side - 1, side - 1), fill=255)
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
    master = Image.open(SRC).convert("RGB")
    w, h = master.size
    print(f"master {w}x{h}")

    # 1) 去掉外层大白边，卡片本体 + 30px 余量 → 正方形
    master_cropped = crop_card_to_square(master)
    print(f"cropped (card + padding) → square {master_cropped.size[0]}x{master_cropped.size[1]}")
    save_png(master_cropped, OUT_CROPPED)
    print(f"  审查图: {OUT_CROPPED}")

    # Play Store 512 — 使用去白边的版本，更饱满
    ps = master_cropped.resize((512, 512), Image.LANCZOS)
    save_png(ps, OUT_PLAY)
    print(f"  Play Store: {OUT_PLAY}")

    for density, size in DENSITIES.items():
        out_dir = RES / density

        # Legacy 方形：直接缩放整图（RGB，无透明）
        base = master_cropped.resize((size, size), Image.LANCZOS).convert("RGB")
        save_webp(base, out_dir / "ic_launcher.webp")

        # Legacy 圆形：圆形蒙版
        round_im = mask_round(base.convert("RGBA"))
        save_webp(round_im, out_dir / "ic_launcher_round.webp")

        # Adaptive 前景：整张裁剪后图铺满 108dp（整图缩放到 size），
        # 卡片白底与 adaptive 背景 #FFFFFF 无缝融合，不会重复白边
        fg = master_cropped.resize((size, size), Image.LANCZOS).convert("RGBA")
        save_webp(fg, out_dir / "ic_launcher_foreground.webp")
        print(f"  {density}: {size}x{size} ok")


if __name__ == "__main__":
    main()
