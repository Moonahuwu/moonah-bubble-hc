"""Makes textures/item/wheel_spin.png (16x16): a colourful prize wheel with a gold pointer. Needs Pillow.
Run from the bubble-neoforge folder."""
import math
from PIL import Image

OUT = "src/main/resources/assets/bubble/textures/item/wheel_spin.png"
COLORS = [(255, 90, 90), (255, 179, 71), (242, 210, 60), (124, 224, 124), (143, 211, 255), (74, 144, 217), (181, 101, 224), (255, 79, 216)]
OUTLINE = (30, 24, 40, 255)
GOLD = (255, 215, 64, 255)
GOLD_LO = (184, 134, 11, 255)

img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
px = img.load()
cx, cy, r = 7.5, 8.5, 6.6
for y in range(16):
    for x in range(16):
        dx, dy = x - cx, y - cy
        d = math.hypot(dx, dy)
        if d <= r + 0.5:
            if d > r - 0.6:
                px[x, y] = OUTLINE
            elif d < 1.3:
                px[x, y] = GOLD
            else:
                a = (math.degrees(math.atan2(dy, dx)) + 360 + 11) % 360
                c = COLORS[int(a // 45) % len(COLORS)]
                shade = 1.0 if dy < 0 else 0.85
                px[x, y] = (int(c[0] * shade), int(c[1] * shade), int(c[2] * shade), 255)
# pointer on top
for x, y, c in [(6, 0, OUTLINE), (7, 0, OUTLINE), (8, 0, OUTLINE), (9, 0, OUTLINE), (7, 1, GOLD), (8, 1, GOLD), (6, 1, OUTLINE), (9, 1, OUTLINE),
                (7, 2, GOLD_LO), (8, 2, GOLD_LO)]:
    px[x, y] = c
img.save(OUT)
print("wrote", OUT)
