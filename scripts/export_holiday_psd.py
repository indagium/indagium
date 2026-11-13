#!/usr/bin/env python3
"""Export authored PSD composites without flattening their transparency.

Developer-only dependency: psd-tools==1.26.1. Source PSDs stay outside the app.
The whole canvas is kept, scaled to WIDTH pixels: the largest placement is about 600 px wide on a 2x screen, so the
full 1668 px canvas only cost memory. The painters crop in PSD canvas units and draw the image back at canvas size.
Usage: python scripts/export_holiday_psd.py --bear /path/IMG_0011.PSD --tulips /path/IMG_0018.PSD
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image
from psd_tools import PSDImage

WIDTH = 1000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bear', type=Path)
    parser.add_argument('--tulips', type=Path)
    args = parser.parse_args()
    if not args.bear and not args.tulips:
        parser.error('provide --bear and/or --tulips')
    root = Path(__file__).resolve().parents[1]
    for theme in ('bear', 'tulips'):
        source = getattr(args, theme)
        if source is None:
            continue
        psd = PSDImage.open(source)
        # Force layer compositing, honoring visibility, authored alpha and layer opacity.
        image = psd.composite(force=True)
        height = round(image.height * WIDTH / image.width)
        # Resample with premultiplied alpha so translucent edges keep their colour.
        image = image.convert('RGBa').resize((WIDTH, height), Image.LANCZOS).convert('RGBA')
        target = root / 'src/desktopMain/resources/holiday' / theme / 'painting.png'
        image.save(target, optimize=True)
        print(json.dumps({'theme': theme, 'source': source.name,
                          'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                          'canvas': list(psd.size), 'size': list(image.size), 'layers': len(psd),
                          'alpha_bounds': image.getchannel('A').getbbox(),
                          'output_sha256': hashlib.sha256(target.read_bytes()).hexdigest()}))


if __name__ == '__main__':
    main()
