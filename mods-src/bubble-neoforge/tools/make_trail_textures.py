"""Draws the ribbon trail textures (assets/bubble/textures/trail/*.png).

Each one is a 128x32 strip: left to right is along the trail (it repeats, so the edges must line up), top to bottom is
across it. Run: python tools/make_trail_textures.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 128, 32
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "bubble", "textures", "trail")
os.makedirs(OUT, exist_ok=True)


def tiled(draw_fn, blur=0):
    """Draws on a 3-wide canvas and keeps the middle, so anything crossing an edge wraps round and it tiles."""
    big = Image.new("RGBA", (W * 3, H), (0, 0, 0, 0))
    for k in range(3):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        draw_fn(layer, ImageDraw.Draw(layer))
        big.alpha_composite(layer, (k * W, 0))
    if blur:
        big = big.filter(ImageFilter.GaussianBlur(blur))
    return big.crop((W, 0, 2 * W, H))


def glow_line(points, color, width, glow):
    def fn(img, d):
        d.line(points, fill=color, width=width, joint="curve")
    soft = tiled(fn, glow)
    sharp = tiled(lambda img, d: d.line(points, fill=(255, 255, 255, 255), width=max(1, width // 2), joint="curve"))
    soft.alpha_composite(soft)
    soft.alpha_composite(sharp)
    return soft


def save(name, img):
    img.save(os.path.join(OUT, name + ".png"))
    print("wrote", name)


# Plasma: a soft cyan beam with a bright wavy core
pts = [(x, H / 2 + 4 * math.sin(x / W * 4 * math.pi)) for x in range(0, W + 1, 2)]
save("ribbon_plasma", glow_line(pts, (60, 200, 255, 255), 10, 4))

# Electric: a jagged yellow-white bolt
random.seed(3)
ys = [H / 2] + [H / 2 + random.uniform(-10, 10) for _ in range(9)] + [H / 2]
pts = [(i * W / 10, y) for i, y in enumerate(ys)]
save("ribbon_electric", glow_line(pts, (255, 235, 90, 255), 4, 3))


# Love: two pink hearts per tile
def heart(d, cx, cy, s, color):
    d.ellipse((cx - s, cy - s * 0.8, cx, cy + s * 0.2), fill=color)
    d.ellipse((cx, cy - s * 0.8, cx + s, cy + s * 0.2), fill=color)
    d.polygon([(cx - s * 0.97, cy - 0.1 * s), (cx + s * 0.97, cy - 0.1 * s), (cx, cy + s * 1.05)], fill=color)


def love(img, d):
    for cx in (32, 96):
        heart(d, cx, 15, 11, (190, 30, 70, 255))
        heart(d, cx, 14, 9, (255, 105, 140, 255))
        d.ellipse((cx - 6, 8, cx - 3, 11), fill=(255, 220, 230, 255))


save("ribbon_love", tiled(love))


# Smoke: soft grey puffs
def smoke(img, d):
    r = random.Random(7)
    for _ in range(70):
        x, y, s = r.uniform(0, W), r.uniform(11, 21), r.uniform(6, 11)
        g = r.randint(160, 215)
        d.ellipse((x - s, y - s, x + s, y + s), fill=(g, g, g + 8, 95))


save("ribbon_smoke", tiled(smoke, 4))


# Fire: flame tongues from the bottom, yellow at the core
def fire(img, d):
    r = random.Random(11)
    for layer, col in ((0, (200, 40, 10, 200)), (1, (255, 120, 20, 230)), (2, (255, 220, 90, 255))):
        for i in range(8):
            cx = i * 16 + 8 + r.uniform(-3, 3)
            h = (26 - layer * 7) * r.uniform(0.75, 1.0)
            w = 9 - layer * 2.5
            d.polygon([(cx - w, H - 2), (cx + w, H - 2), (cx + r.uniform(-3, 3), H - 2 - h)], fill=col)


save("ribbon_fire", tiled(fire, 1.2))


# Stars: gold five-pointed stars, two sizes
def star(d, cx, cy, ro, color):
    ri = ro * 0.45
    pts = []
    for k in range(10):
        a = -math.pi / 2 + k * math.pi / 5
        rr = ro if k % 2 == 0 else ri
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=color)


def stars(img, d):
    for cx, cy, s in ((20, 16, 11), (58, 10, 6), (84, 18, 10), (112, 9, 5), (40, 25, 4)):
        star(d, cx, cy, s + 1.5, (200, 130, 20, 255))
        star(d, cx, cy, s, (255, 215, 80, 255))


save("ribbon_stars", tiled(stars))


# Bubbles: light blue rings with a shine
def bubbles(img, d):
    for cx, cy, s in ((16, 16, 10), (44, 11, 6), (64, 20, 8), (90, 14, 11), (116, 22, 5)):
        d.ellipse((cx - s, cy - s, cx + s, cy + s), fill=(140, 220, 255, 60), outline=(170, 235, 255, 255), width=2)
        d.ellipse((cx - s * 0.55, cy - s * 0.6, cx - s * 0.15, cy - s * 0.2), fill=(255, 255, 255, 230))


save("ribbon_bubbles", tiled(bubbles))


def bands(colors):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    n = len(colors)
    for i, c in enumerate(colors):
        d.rectangle((0, round(i * H / n), W, round((i + 1) * H / n) - 1), fill=c + (255,))
    return img


save("ribbon_rainbow", bands([(228, 3, 3), (255, 140, 0), (255, 237, 0), (0, 128, 38), (36, 64, 142), (115, 41, 130)]))
save("ribbon_trans", bands([(91, 206, 250), (245, 169, 184), (255, 255, 255), (245, 169, 184), (91, 206, 250)]))


# LOL: white letters with a black outline, twice per tile
def lol(img, d):
    try:
        font = ImageFont.truetype("impact.ttf", 26)
    except OSError:
        font = ImageFont.truetype("arialbd.ttf", 24)
    for cx in (32, 96):
        w = d.textlength("LOL", font=font)
        d.text((cx - w / 2, 1), "LOL", font=font, fill=(255, 255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0, 255))


save("ribbon_lol", tiled(lol))
