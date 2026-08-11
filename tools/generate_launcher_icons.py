#!/usr/bin/env python3
"""
R-DeepCode Launcher Icon 位图生成器 v2
  对话气泡 + 代码符号 </> + 科技蓝紫渐变 (#165DFF → #7B61FF)
  背景 = 深蓝黑 #0D1117
  完全程序化 Pillow 绘制，100% 像素锐利。
"""
from PIL import Image, ImageDraw, ImageFilter
import os, math

BG = (0x0D, 0x11, 0x17, 0xFF)       # 深蓝黑
BLUE = (0x16, 0x5D, 0xFF, 0xFF)      # 科技蓝
PURPLE = (0x7B, 0x61, 0xFF, 0xFF)    # 暗紫
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)     # 代码符号白
SHADOW = (0x00, 0x00, 0x00, 0x66)    # 投影 40% alpha

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
FG_SCALE = 2.25  # foreground = 108/48


def lerp_color(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(4))


def draw_gradient_bubble(img, cx, cy, bw, bh):
    """
    在 (cx,cy) 中心绘制渐变圆角对话气泡 + 左下尾巴。
    bw × bh = 气泡主体宽高。圆角 r = bh * 0.18。
    渐变 135°：左上蓝 → 右下紫。
    """
    # 气泡主体矩形坐标
    left = cx - bw // 2
    top = cy - bh // 2
    right = cx + bw // 2
    bottom = cy + bh // 2
    r = int(bh * 0.18)

    # 尾巴坐标（左下角）
    tail_w = int(bw * 0.12)
    tail_h = int(bh * 0.16)
    tail_top = bottom - int(bh * 0.05)
    tail_x1 = left + int(bw * 0.16)
    tail_x2 = tail_x1 + tail_w
    tail_x3 = tail_x1 - int(tail_w * 0.3)

    # 创建一个渐变 mask 气泡
    # 1) 渐变图像（按对角线 135° 逐行/逐列插值）
    grad = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    gp = grad.load()
    diag = bw + bh
    for x in range(bw):
        for y in range(bh):
            t = (x + y) / diag if diag > 0 else 0
            gp[x, y] = lerp_color(BLUE, PURPLE, t)

    # 2) 气泡 alpha mask（圆角矩形 + 尾巴）
    mask = Image.new("L", (bw, bh + tail_h), 0)
    md = ImageDraw.Draw(mask)
    # 圆角矩形
    md.rounded_rectangle([0, 0, bw - 1, bh - 1], radius=r, fill=255)
    # 尾巴三角
    tail_pts = [
        (tail_x1 - left, tail_top - top),        # 左上
        (tail_x2 - left, tail_top - top),         # 右上
        (tail_x3 - left, tail_top - top + tail_h),# 下尖
    ]
    md.polygon(tail_pts, fill=255)

    # 3) 把渐变裁到气泡形状
    # 渐变图是 bw×bh，mask 是 bw×(bh+tail_h)，先 paste 渐变到一张大透明画布
    bubble = Image.new("RGBA", (bw, bh + tail_h), (0, 0, 0, 0))
    # 渐变只覆盖 bh 高，尾巴区域也要有渐变（延伸）
    grad_full = Image.new("RGBA", (bw, bh + tail_h), (0, 0, 0, 0))
    grad_full.paste(grad, (0, 0))
    # 尾巴区域用纯紫色（渐变末端）
    tail_grad = Image.new("RGBA", (bw, tail_h), PURPLE)
    grad_full.paste(tail_grad, (0, bh))

    bubble.paste(grad_full, (0, 0))
    # apply mask
    bubble.putalpha(mask)

    # 4) 投影：复制气泡做模糊 + 下偏移
    shadow = bubble.copy()
    shadow_canvas = Image.new("RGBA", bubble.size, (0, 0, 0, 0))
    # 把气泡的形状填黑色做投影
    shadow_alpha = mask.copy()
    shadow_rgba = Image.new("RGBA", bubble.size, SHADOW)
    shadow_rgba.putalpha(shadow_alpha)
    # 模糊
    shadow_blur = shadow_rgba.filter(ImageFilter.GaussianBlur(radius=max(2, int(bw * 0.02))))
    # 下偏移
    offset_y = int(bh * 0.03)
    shadow_paste = Image.new("RGBA", bubble.size, (0, 0, 0, 0))
    shadow_paste.alpha_composite(shadow_blur, (0, offset_y))

    # 5) 合成到 img
    # 创建一个气泡大小的透明层用于在 img 上 paste
    layer = Image.new("RGBA", bubble.size, (0, 0, 0, 0))
    layer.alpha_composite(shadow_paste)
    layer.alpha_composite(bubble)

    # 内描边 1px 白 15%
    overlay = Image.new("RGBA", bubble.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rounded_rectangle([0, 0, bw - 1, bh - 1], radius=r, outline=(255, 255, 255, 38), width=max(1, int(bw * 0.006)))
    layer.alpha_composite(overlay)

    # paste 到 img（居中对齐）
    px = cx - bw // 2
    py = cy - (bh + tail_h) // 2
    img.alpha_composite(layer, (px, py))

    return (left, top, right, bottom)


def draw_code_symbol(img, cx, cy, cw, ch):
    """
    在 (cx,cy) 居中绘制白色 </> 代码符号。
    由 3 个 path 组成：< / >
    cw × ch = 代码符号外框宽高。
    """
    d = ImageDraw.Draw(img)
    # 粗细 = ch * 0.10
    th = max(1, int(ch * 0.10))
    half_w = cw // 2
    half_h = ch // 2

    # < 左尖括号：两条线组成 V 形开口向右
    left_top = (cx - half_w, cy - half_h)
    mid = (cx - half_w // 3, cy)
    left_bot = (cx - half_w, cy + half_h)
    # 画粗线条 V
    d.line([left_top, mid], fill=WHITE, width=th)
    d.line([mid, left_bot], fill=WHITE, width=th)
    # 补充圆角端点
    r_end = th // 2
    for pt in [left_top, mid, left_bot]:
        d.ellipse([pt[0]-r_end, pt[1]-r_end, pt[0]+r_end, pt[1]+r_end], fill=WHITE)

    # > 右尖括号
    right_top = (cx + half_w, cy - half_h)
    mid_r = (cx + half_w // 3, cy)
    right_bot = (cx + half_w, cy + half_h)
    d.line([right_top, mid_r], fill=WHITE, width=th)
    d.line([mid_r, right_bot], fill=WHITE, width=th)
    for pt in [right_top, mid_r, right_bot]:
        d.ellipse([pt[0]-r_end, pt[1]-r_end, pt[0]+r_end, pt[1]+r_end], fill=WHITE)

    # / 中间斜线
    slash_top = (cx + int(cw * 0.06), cy - half_h - int(ch * 0.05))
    slash_bot = (cx - int(cw * 0.06), cy + half_h + int(ch * 0.05))
    d.line([slash_top, slash_bot], fill=WHITE, width=max(2, int(th * 0.8)))
    for pt in [slash_top, slash_bot]:
        d.ellipse([pt[0]-r_end, pt[1]-r_end, pt[0]+r_end, pt[1]+r_end], fill=WHITE)


def make_foreground(fg_px):
    """透明底 + 渐变气泡 + 白色 </>"""
    canvas = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
    # 气泡占画布 66%，居中
    bw = int(fg_px * 0.64)
    bh = int(fg_px * 0.50)
    cx = fg_px // 2
    cy = fg_px // 2 - int(fg_px * 0.02)  # 微上偏补偿尾巴

    draw_gradient_bubble(canvas, cx, cy, bw, bh)

    # 代码符号在气泡内居中
    cw = int(bw * 0.48)
    ch = int(bh * 0.36)
    draw_code_symbol(canvas, cx, cy - int(fg_px * 0.02), cw, ch)

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
        fg_img.save(f"{RES_DIR}/mipmap-{suffix}/ic_launcher_foreground.webp", "WEBP", lossless=True, quality=100)
        fg_img.save(f"{DESIGN_DIR}/ic_launcher_foreground_{suffix}_{fg_px}x{fg_px}.png", "PNG", optimize=True)
        composite(fg_img, f"{RES_DIR}/mipmap-{suffix}/ic_launcher.webp", round_mask=False)
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
