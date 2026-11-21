"""Makes the case item textures (16x16): textures/item/case.png (the dark case with its handle and latches) and
textures/item/case_band.png (a white band and corner caps, tinted in game with the case's colour).
Needs Pillow. Run from the bubble-neoforge folder."""
from PIL import Image

OUT = "src/main/resources/assets/bubble/textures/item/"

OUTLINE = (24, 26, 31, 255)
BODY = (62, 67, 77, 255)
BODY_HI = (88, 95, 108, 255)
BODY_LO = (44, 48, 56, 255)
METAL = (190, 196, 204, 255)
METAL_LO = (120, 126, 134, 255)

base = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
band = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
b, t = base.load(), band.load()

# handle (top)
for x in range(6, 10):
    b[x, 2] = OUTLINE
for y in (3, 4):
    b[5, y] = OUTLINE
    b[10, y] = OUTLINE
    b[6, y] = METAL_LO
    b[9, y] = METAL_LO
for x in range(6, 10):
    b[x, 3] = METAL if y == 3 else b[x, 3]

# case body: x 1..14, y 5..13 with rounded corners
for y in range(5, 14):
    for x in range(1, 15):
        corner = (x in (1, 14)) and (y in (5, 13))
        if corner:
            continue
        edge = x in (1, 14) or y in (5, 13) or ((x in (2, 13)) and (y in (5, 13)))
        if edge:
            b[x, y] = OUTLINE
        elif y == 6:
            b[x, y] = BODY_HI
        elif y == 12:
            b[x, y] = BODY_LO
        else:
            b[x, y] = BODY
# rounded corner pixels inside
for (x, y) in ((2, 6), (13, 6), (2, 12), (13, 12)):
    b[x, y] = OUTLINE

# latches
for x in (4, 11):
    b[x, 7] = METAL
    b[x, 8] = METAL_LO

# coloured band through the middle (tinted) + small corner caps
for x in range(2, 14):
    shade = 255 if x not in (2, 13) else 215
    t[x, 9] = (shade, shade, shade, 255)
    t[x, 10] = (200, 200, 200, 255)
for (x, y) in ((3, 7), (12, 7), (3, 11), (12, 11)):
    t[x, y] = (235, 235, 235, 255)

base.save(OUT + "case.png")
band.save(OUT + "case_band.png")
print("wrote", OUT + "case.png", OUT + "case_band.png")
