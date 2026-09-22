"""Lift the calendar icons off their bar fill, so the Calendar page can show the art alone.

The calendar scan saves each event's icon exactly as it appears in the in-game chart: the art still
sitting on the bar's flat fill, with a sliver of chart background at the edges. Keying that fill out
by colour does not work on this set -- the crop contains two flat colours (the bar and the chart
behind it), several bars carry a gradient, and the art itself often uses the same yellow -- so this
uses rembg, which segments the foreground instead of matching colours.

    python tools/clean-calendar-icons.py            # clean whatever is not cleaned yet
    python tools/clean-calendar-icons.py --force    # redo them all

Output goes to data/calendar-icons/clean/<same name>.png, which the desktop Calendar page prefers
over the raw crop and falls back from when it is missing. Both folders are workspace data; neither
is committed.
"""
import sys
from pathlib import Path

from PIL import Image
from rembg import remove

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "data" / "calendar-icons"
TARGET = SOURCE / "clean"


def main() -> int:
    force = "--force" in sys.argv
    if not SOURCE.is_dir():
        print(f"no icons at {SOURCE}")
        return 1
    TARGET.mkdir(parents=True, exist_ok=True)

    cleaned = skipped = 0
    for icon in sorted(SOURCE.glob("*.png")):
        out = TARGET / icon.name
        if out.exists() and not force:
            skipped += 1
            continue
        cut = remove(Image.open(icon).convert("RGBA"))
        box = cut.getbbox()
        if box:
            cut = cut.crop(box)
        # Square it so every icon lands the same size in the row, whatever shape its art is.
        side = max(cut.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(cut, ((side - cut.width) // 2, (side - cut.height) // 2))
        square.save(out)
        cleaned += 1
        print(f"cleaned {icon.name}")

    print(f"{cleaned} cleaned, {skipped} already done -> {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
