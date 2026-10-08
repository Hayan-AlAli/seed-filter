"""Draws the Seed Filter icon (a sprouting seed under a magnifying glass) as 32x32 pixel art
and upscales it without smoothing.

Run: python branding/make_icon.py  ->  branding/icon.png (512), branding/icon-128.png (128)
"""
import math
from pathlib import Path
from PIL import Image

N = 32
BG, BG_EDGE = (27, 38, 32), (52, 70, 58)
RIM, RIM_DARK, RIM_LIGHT = (206, 213, 221), (122, 132, 145), (250, 252, 255)
GLASS, GLASS_DARK, GLARE = (46, 78, 90), (36, 62, 74), (196, 236, 248)
WOOD, WOOD_DARK, WOOD_LIGHT = (128, 88, 48), (74, 48, 24), (166, 118, 66)
COLLAR, COLLAR_DARK = (184, 192, 200), (112, 120, 132)

CX, CY = 13.5, 13.5      # lens centre
R_OUT, R_IN = 11.4, 9.2  # rim

# hand-drawn sprouting seed; '.' = keep what is underneath
SPRITE = [
    ".LL......LL.",
    "LLLL....LLLl",
    "LLLLl..lLLll",
    ".lLLls.sLll.",
    "..lllsslll..",
    ".....ss.....",
    "....OOOO....",
    "...OYYSSO...",
    "..OYYSSSDO..",
    "..OYSSSSDO..",
    "..OSSSSSDO..",
    "..OSSSSDDO..",
    "...OSSDDO...",
    "....ODDO....",
    ".....OO.....",
]
SPRITE_COLORS = {
    "L": (124, 206, 72), "l": (64, 140, 40), "s": (70, 120, 40),
    "O": (86, 54, 18), "Y": (248, 216, 130), "S": (224, 168, 64), "D": (170, 116, 40),
}


def in_tile(x, y, r=5.0):
    cx = min(max(x + 0.5, r), N - r)
    cy = min(max(y + 0.5, r), N - r)
    return (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r


def draw():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()

    # rounded tile with a one-pixel lighter border
    for y in range(N):
        for x in range(N):
            if in_tile(x, y):
                edge = any(not (0 <= x + dx < N and 0 <= y + dy < N and in_tile(x + dx, y + dy))
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                px[x, y] = BG_EDGE if edge else BG

    # handle: diagonal band towards the bottom-right, metal collar next to the rim
    for y in range(N):
        for x in range(N):
            u = ((x + 0.5 - CX) + (y + 0.5 - CY)) / math.sqrt(2)  # along
            v = ((x + 0.5 - CX) - (y + 0.5 - CY)) / math.sqrt(2)  # across
            if R_OUT - 0.5 <= u <= 22.0 and abs(v) <= 2.3 and in_tile(x, y) and 1 <= x <= N - 2 and 1 <= y <= N - 2:
                if u <= R_OUT + 2.0:
                    px[x, y] = COLLAR_DARK if v > 0.8 else COLLAR
                else:
                    px[x, y] = WOOD_DARK if abs(v) > 1.5 else WOOD_LIGHT if v < -0.3 else WOOD

    # lens: shaded rim (light top-left, dark bottom-right), tinted glass, glare streak
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - CX, y + 0.5 - CY
            d = math.hypot(dx, dy)
            if R_IN <= d <= R_OUT:
                t = (dx + dy) / max(d, 1e-6)
                px[x, y] = RIM_LIGHT if t < -1.0 else RIM_DARK if t > 0.8 else RIM
            elif d < R_IN:
                px[x, y] = GLASS_DARK if dx + dy > 7 else GLASS
    for x, y in [(7, 8), (8, 7), (6, 9), (9, 6), (6, 10), (10, 6)]:
        px[x, y] = GLARE

    # seed sprite, centred in the glass
    ox, oy = 8, 6
    for j, row in enumerate(SPRITE):
        for i, c in enumerate(row):
            if c != ".":
                px[ox + i, oy + j] = SPRITE_COLORS[c]
    return img


if __name__ == "__main__":
    out = Path(__file__).parent
    icon = draw()
    icon.resize((512, 512), Image.NEAREST).save(out / "icon.png")
    icon.resize((128, 128), Image.NEAREST).save(out / "icon-128.png")
    print("wrote", out / "icon.png", out / "icon-128.png")
