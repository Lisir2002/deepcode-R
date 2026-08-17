#!/usr/bin/env python3
"""Build Android launcher icons from master artwork for R-CodeCore.

v3 策略 —— 彻底消除"误裁切导致残缺"风险（用户反馈 rc138 图标右下 R 尾巴被截断）：

  前两版问题回顾：
  - v1：整图缩放到 66dp 安全区 → 外层 ~230px 大白边，手机看起"画中画"显小
  - v2：亮度扫描(L<245) + 30px padding → 卡片的阴影渐变过渡（246~250 lum）被扫漏，
         padding 不够补偿阴影，边缘 lum=246 直接到图像最外层，方形蒙版截断阴影，
         出现"R 紫色尾巴缺一块"。

  v3（本版）：零裁切。
  1) 整图 2048² 从不 crop；
  2) Legacy 方形/圆形：整图直接缩放到目标尺寸，100% 像素保留，永不残缺；
     代价是保留了原图最外层 ~230px 卡片外白边，但用户视觉上 = 图标周围再均匀留白
     （手机桌面方形图标刚好把外白边吃成 0），卡片本体 100% 完整。
  3) Adaptive 前景：整图按 66dp 安全区比例（66/108=0.611）缩放后居中，
     四周透明。Adaptive 背景为纯白 @color/ic_launcher_background，
     卡片外白边 + adaptive 白背景 = 视觉无缝，logo 始终完整位于安全区中央，
     任何系统 mask（圆/圆角/squircle/水滴形）都不切到卡片内的 R 图形。
  4) Play Store 512：整图缩放，保持完整。

  核心原则：宁可留白，不许残缺。

Densities: mdpi=48 / hdpi=72 / xhdpi=96 / xxhdpi=144 / xxxhdpi=192
"""
from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "artwork" / "rcodecore-launcher-master.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
OUT_PLAY = ROOT / "artwork" / "rcodecore-launcher-playstore-512.png"
OUT_REVIEW = ROOT / "artwork" / "rcodecore-launcher-v3-review.png"   # 生成 192 预览拼图

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

SAFE_ZONE_RATIO = 66.0 / 108.0   # Android adaptive：66dp 内安全区 / 108dp 总画布


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
    assert w == h, f"master 必须是正方形，实际 {w}x{h}"
    print(f"master {w}x{h}（v3: 零裁切·整图缩放）")

    # Play Store 512 — 整图缩放，100% 完整
    ps = master.resize((512, 512), Image.LANCZOS)
    save_png(ps, OUT_PLAY)
    print(f"  Play Store: {OUT_PLAY}")

    preview_items: list[tuple[str, Image.Image]] = []  # 192x192 三版拼图用

    for density, size in DENSITIES.items():
        out_dir = RES / density

        # Legacy 方形：整图 → size（RGB，无透明，100% 像素保留）
        base = master.resize((size, size), Image.LANCZOS).convert("RGB")
        save_webp(base, out_dir / "ic_launcher.webp")

        # Legacy 圆形：整图 → 圆形蒙版
        round_im = mask_round(base.convert("RGBA"))
        save_webp(round_im, out_dir / "ic_launcher_round.webp")

        # Adaptive 前景：整图 缩放到 66dp 安全区大小后居中，四周透明
        # → 卡片外的"大白边"在 66/108 缩放后实际占安全区的 77%，
        #    卡片外剩的 23% 左右是透明，系统任何 mask 不会切到卡片。
        inner = int(size * SAFE_ZONE_RATIO)
        fg_canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        fg = master.resize((inner, inner), Image.LANCZOS).convert("RGBA")
        offset = (size - inner) // 2
        fg_canvas.paste(fg, (offset, offset))
        save_webp(fg_canvas, out_dir / "ic_launcher_foreground.webp")

        print(f"  {density}: {size}x{size} ok (base size/inner safe={inner})")

        # 仅 xxxhdpi 收集到预览拼图
        if size == 192:
            preview_items.append(("square", base.convert("RGBA")))
            preview_items.append(("round", round_im))
            preview_items.append(("foreground", fg_canvas))

    # 192 预览拼图（3 格 + 每格写标签）
    cell_w, cell_h = 192, 192
    gap, label_h = 10, 28
    board = Image.new("RGBA", (cell_w * 3 + gap * 4, cell_h + label_h + gap * 2),
                      (235, 235, 235, 255))
    draw = ImageDraw.Draw(board)
    for i, (name, im) in enumerate(preview_items):
        x = gap + i * (cell_w + gap)
        y = gap
        # 加一个浅灰棋盘表示透明区
        checker = Image.new("RGBA", (cell_w, cell_h), (255, 255, 255, 255))
        cw = ImageDraw.Draw(checker)
        for cy in range(0, cell_h, 16):
            for cx in range(0, cell_w, 16):
                if ((cx // 16) + (cy // 16)) % 2 == 1:
                    cw.rectangle([cx, cy, cx + 15, cy + 15], fill=(220, 220, 220, 255))
        board.paste(checker, (x, y), checker)
        board.paste(im.resize((cell_w, cell_h)), (x, y), im.resize((cell_w, cell_h)))
        draw.rectangle([x, y, x + cell_w - 1, y + cell_h - 1], outline=(0, 0, 0, 120), width=1)
        draw.text((x, y + cell_h + 6), name, fill=(30, 30, 30, 255))
    save_png(board, OUT_REVIEW)
    print(f"  预览拼图: {OUT_REVIEW}")


if __name__ == "__main__":
    main()
