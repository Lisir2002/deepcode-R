#!/usr/bin/env python3
"""Build Android launcher icons from master artwork for R-CodeCore rebrand.

Generates for each density:
  ic_launcher.webp          - legacy square icon (masked on newer launchers)
  ic_launcher_round.webp    - legacy round mask
  ic_launcher_foreground.webp - adaptive icon foreground (transparent background)

Also exports:
  artwork/rcodecore-launcher-playstore-512.png (Play Store listing, 512x512)
"""
from __future__ import annotations

import os
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "artwork" / "rcodecore-launcher-master.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
OUT_PLAY = ROOT / "artwork" / "rcodecore-launcher-playstore-512.png"

# Android launcher icon base sizes (adaptive-icon uses 108dp, but legacy PNG mipmaps are:
# mdpi=48px, hdpi=72px, xhdpi=96px, xxhdpi=144px, xxxhdpi=192px)
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def square_crop_cover(im: Image.Image) -> Image.Image:
    """Center-crop to square with COVER strategy (fill short edge, crop long)."""
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def to_rgba(im: Image.Image) -> Image.Image:
    if im.mode != "RGBA":
        im = im.convert("RGBA")
    return im


def mask_round(im: Image.Image) -> Image.Image:
    """Punch out a circle mask inside the square for ic_launcher_round."""
    im = to_rgba(im)
    side = im.size[0]
    mask = Image.new("L", (side, side), 0)
    d = ImageDraw.Draw(mask)
    # antialias via slightly inset circle? We'll use full circle.
    pad = 0
    d.ellipse((pad, pad, side - pad, side - pad), fill=255)
    out = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def make_foreground(im: Image.Image) -> Image.Image:
    """Make adaptive-icon foreground: keep artwork but whiten pure-white background into transparent.

    Strategy: replace pixels whose RGB is >=250 (very white) with transparent.
    Then remove ~6% outer padding so foreground fills the 108dp inner 72dp safe zone correctly.
    """
    im = to_rgba(im)
    data = list(im.getdata())
    new_data = []
    for r, g, b, a in data:
        if r >= 245 and g >= 245 and b >= 245:
            new_data.append((r, g, b, 0))
        else:
            new_data.append((r, g, b, a))
    im.putdata(new_data)

    # Zoom in 1.15x by cropping a central 87% region and resizing back (center zoom)
    w, h = im.size
    crop = int(w * 0.075)
    im = im.crop((crop, crop, w - crop, h - crop))
    im = im.resize((w, h), Image.LANCZOS)
    return im


def save_webp(im: Image.Image, path: Path, quality: int = 92) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "WEBP", quality=quality, method=6)


def save_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)


def main() -> None:
    assert SRC.exists(), f"Missing master: {SRC}"
    master = Image.open(SRC)
    master_sq = square_crop_cover(master)  # 1024x1024 ideally

    # Play Store 512
    ps = master_sq.resize((512, 512), Image.LANCZOS)
    save_png(ps.convert("RGBA"), OUT_PLAY)
    print(f"wrote {OUT_PLAY}")

    for density, size in DENSITIES.items():
        base = master_sq.resize((size, size), Image.LANCZOS).convert("RGBA")
        out_dir = RES / density

        # ic_launcher.webp  (legacy square, RGBA)
        save_webp(base, out_dir / "ic_launcher.webp")

        # ic_launcher_round.webp (circle mask, transparent bg)
        round_im = mask_round(base)
        save_webp(round_im, out_dir / "ic_launcher_round.webp")

        # ic_launcher_foreground.webp (transparent bg, zoomed in)
        hi_src = master_sq.resize((size, size), Image.LANCZOS)
        fg = make_foreground(hi_src)
        save_webp(fg, out_dir / "ic_launcher_foreground.webp")
        print(f"{density}: {size}x{size} ok")


if __name__ == "__main__":
    main()
