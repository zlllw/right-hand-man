#!/usr/bin/env python3
"""手信启动图标生成器（方案 E：纸白底 + 甲骨文「手」+ 蓝色提示符）。

素材：site/assets/dock_mark_left.png（46×74 的「手」alpha 蒙版）。
输出：
  drawable-{dpi}/ic_rhm_foreground.png   自适应图标前景（108dp，透明底）
  drawable-{dpi}/ic_rhm_monochrome.png   系统主题单色图标（108dp，纯白剪影）
  mipmap-{dpi}/ic_launcher.png           旧版方形图标（裁 72dp 可视区，圆角）
  mipmap-{dpi}/ic_launcher_round.png     旧版圆形图标
"""

from PIL import Image, ImageDraw
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "site" / "assets" / "dock_mark_left.png"
RES = ROOT / "app" / "src" / "main" / "res"

PAPER = (245, 244, 240, 255)
INK_TOP, INK_BOT = (5, 6, 9), (43, 47, 55)
BLUE_TOP, BLUE_BOT = (11, 67, 214), (111, 163, 255)
UL_TOP, UL_BOT = (18, 20, 26), (58, 62, 71)

# 108dp 画布上的几何（内容压在 72dp 可视区内）
HAND_W = 24.84
HAND_H = HAND_W * 74 / 46
HAND_X = (108 - HAND_W) / 2
HAND_Y = 0.203 * 108
PROMPT_W = 25.92
PROMPT_H = PROMPT_W * 72 / 100
PROMPT_X = (108 - PROMPT_W) / 2
PROMPT_Y = 108 - 0.21 * 108 - PROMPT_H

SS = 4  # 超采样倍数，最后 LANCZOS 缩回目标尺寸
DPI = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

HAND_ALPHA = Image.open(SRC).getchannel("A")


def vgrad(w, h, top, bot):
    strip = Image.new("RGB", (1, h))
    px = strip.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        px[0, y] = tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3))
    return strip.resize((w, h)).convert("RGBA")


def prompt_layers(w, h):
    """返回 (chevron_mask, underline_mask, caret_mask)，坐标按 viewBox 100×72 缩放。"""
    k = w / 100.0
    sw = max(1, round(13 * k))
    chevron = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(chevron)
    pts = [(round(x * k), round(y * k)) for x, y in ((14, 10), (50, 36), (14, 62))]
    d.line(pts, fill=255, width=sw, joint="curve")
    for x, y in pts:
        r = sw // 2
        d.ellipse((x - r, y - r, x + r, y + r), fill=255)

    underline = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(underline)
    x0, x1, y = round(62 * k), round(92 * k), round(60 * k)
    d.line((x0, y, x1, y), fill=255, width=sw)
    r = sw // 2
    d.ellipse((x0 - r, y - r, x0 + r, y + r), fill=255)
    d.ellipse((x1 - r, y - r, x1 + r, y + r), fill=255)

    caret = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(caret)
    d.rounded_rectangle(
        (round(95 * k), round(56 * k), round(99.4 * k), round(68 * k)),
        radius=max(1, round(1.6 * k)),
        fill=255,
    )
    return chevron, underline, caret


def render_art(canvas, k, mono=False):
    """canvas 为 108dp 设计图的超采样画布，k 为 px/dp。"""
    hand = HAND_ALPHA.resize((round(HAND_W * k), round(HAND_H * k)), Image.LANCZOS)
    hx, hy = round(HAND_X * k), round(HAND_Y * k)
    if mono:
        hand_grad = Image.new("RGBA", hand.size, (255, 255, 255, 255))
    else:
        hand_grad = vgrad(hand.size[0], hand.size[1], INK_TOP, INK_BOT)
    canvas.alpha_composite(
        Image.composite(hand_grad, Image.new("RGBA", hand_grad.size, (0, 0, 0, 0)), hand), (hx, hy)
    )

    pw, ph = round(PROMPT_W * k), round(PROMPT_H * k)
    px, py = round(PROMPT_X * k), round(PROMPT_Y * k)
    chevron, underline, caret = prompt_layers(pw, ph)

    def stamp(mask, top, bot):
        grad = Image.new("RGBA", (pw, ph), (255, 255, 255, 255)) if mono else vgrad(pw, ph, top, bot)
        layer = Image.composite(grad, Image.new("RGBA", (pw, ph), (0, 0, 0, 0)), mask)
        canvas.alpha_composite(layer, (px, py))

    stamp(chevron, BLUE_TOP, BLUE_BOT)
    stamp(underline, UL_TOP, UL_BOT)
    stamp(caret, BLUE_TOP, BLUE_BOT)


def render(size, crop=False, bg=False, round_mask=False, mono=False):
    units = 72 if crop else 108
    k = size * SS / units
    canvas = Image.new("RGBA", (round(units * k), round(units * k)), (0, 0, 0, 0))

    # 背景
    if bg:
        d = ImageDraw.Draw(canvas)
        box = (0, 0, canvas.size[0] - 1, canvas.size[1] - 1)
        if round_mask:
            d.ellipse(box, fill=PAPER)
        elif crop:
            d.rounded_rectangle(box, radius=round(canvas.size[0] * 0.22), fill=PAPER)
        else:
            d.rectangle(box, fill=PAPER)

    # 内容先画到与 canvas 同大的透明层，裁切时整体平移
    tmp = Image.new("RGBA", (round(108 * k), round(108 * k)), (0, 0, 0, 0))
    render_art(tmp, k, mono=mono)
    if crop:
        box = (round(18 * k), round(18 * k), round(90 * k), round(90 * k))
        tmp = tmp.crop(box)
    canvas.alpha_composite(tmp, (0, 0))

    return canvas.resize((size, size), Image.LANCZOS) if canvas.size != (size, size) else canvas


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


def main():
    for dpi, scale in DPI.items():
        fg = round(108 * scale)
        save(render(fg), RES / f"drawable-{dpi}" / "ic_rhm_foreground.png")
        save(render(fg, mono=True), RES / f"drawable-{dpi}" / "ic_rhm_monochrome.png")

        legacy = round(48 * scale)
        save(render(legacy, crop=True, bg=True), RES / f"mipmap-{dpi}" / "ic_launcher.png")
        save(render(legacy, crop=True, bg=True, round_mask=True), RES / f"mipmap-{dpi}" / "ic_launcher_round.png")
        print(f"{dpi}: foreground {fg}px, legacy {legacy}px")


if __name__ == "__main__":
    main()