#!/usr/bin/env python3
"""Draw the TV launcher banner (320x180 px at xhdpi), which has to carry the app's name.

  .venv/bin/python tools/make_art.py      # needs Pillow and a Noto Sans Bold font

The launcher icon needs no script: it is a vector (res/drawable/ic_launcher_foreground.xml).
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-xhdpi", "banner.png")
FONT = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
BACKGROUND, ACCENT, TEXT = (16, 20, 28), (138, 180, 248), (242, 244, 248)
W, H, S = 320, 180, 4   # drawn S times larger, then scaled down for smooth edges


def main() -> None:
    img = Image.new("RGB", (W * S, H * S), BACKGROUND)
    d = ImageDraw.Draw(img)
    s = lambda v: int(v * S)  # noqa: E731

    # The remote: a rounded bar with a ring for the D-pad and two buttons.
    x, y, w, h = 44, 34, 46, 112
    d.rounded_rectangle([s(x), s(y), s(x + w), s(y + h)], radius=s(18), fill=ACCENT)
    cx, cy = x + w / 2, y + 30
    d.ellipse([s(cx - 13), s(cy - 13), s(cx + 13), s(cy + 13)], fill=BACKGROUND)
    d.ellipse([s(cx - 6), s(cy - 6), s(cx + 6), s(cy + 6)], fill=ACCENT)
    for by in (y + 62, y + 80):
        d.ellipse([s(cx - 5), s(by - 5), s(cx + 5), s(by + 5)], fill=BACKGROUND)

    font = ImageFont.truetype(FONT, s(27))
    text = "Remotesmith"
    box = d.textbbox((0, 0), text, font=font)
    d.text((s(110), (H * S - (box[3] - box[1])) // 2 - box[1]), text, font=font, fill=TEXT)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
    print(OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
