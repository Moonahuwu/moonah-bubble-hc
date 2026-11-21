"""Draws assets/bubble/textures/misc/revive.png: the REVIVE sign shown over downed players. Yellow and black only, so
the game can tint the yellow towards red as their time runs out (black stays black)."""
import os
from PIL import Image, ImageDraw, ImageFont

S = 4  # draw big, then scale down for smooth edges
W = 128
img = Image.new("RGBA", (W * S, W * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
P = lambda pts: [(x * S, y * S) for x, y in pts]
YELLOW = (255, 206, 38, 255)
BLACK = (14, 14, 16, 255)
# outer black sign: a box on top of a downward arrow
d.polygon(P([(6, 6), (122, 6), (122, 62), (64, 124), (6, 62)]), fill=BLACK)
# the yellow arrow below the word
d.polygon(P([(14, 56), (114, 56), (114, 61), (64, 114), (14, 61)]), fill=YELLOW)
# REVIVE in yellow across the black band
here = os.path.dirname(os.path.abspath(__file__))
font_path = next(p for p in ["C:/Windows/Fonts/impact.ttf", "C:/Windows/Fonts/arialbd.ttf"] if os.path.exists(p))
size = 60 * S
while True:
    f = ImageFont.truetype(font_path, size)
    box = d.textbbox((0, 0), "REVIVE", font=f)
    if box[2] - box[0] <= 104 * S and box[3] - box[1] <= 40 * S:
        break
    size -= S
tw, th = box[2] - box[0], box[3] - box[1]
d.text(((W * S - tw) / 2 - box[0], (12 + (40 - th / S) / 2) * S - box[1]), "REVIVE", font=f, fill=YELLOW)
out = os.path.join(here, "..", "src", "main", "resources", "assets", "bubble", "textures", "misc", "revive.png")
img.resize((W, W), Image.LANCZOS).save(out)
print("wrote", os.path.normpath(out))
