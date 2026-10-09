#!/usr/bin/env python3
"""Deterministic Android asset sizing only; never redraw or clean the source art.

Requires Pillow 12.3.0. Run from any directory. --check verifies committed assets.
--previews DIRECTORY writes host-only mask and density previews for review.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path

from PIL import Image, ImageDraw

APP = Path(__file__).resolve().parents[1]
SOURCE = APP / "artwork/jarvys-app-icon-source.png"
SOURCE_SHA256 = "5e0f5ae21b58bb4fbdea80abc80af69cea3095354374293e5f1b06fe969558a4"
RES = APP / "app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
BACKGROUND = (17, 24, 39, 255)
FILTER = Image.Resampling.LANCZOS


def load_source() -> Image.Image:
    assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == SOURCE_SHA256, "Source artwork changed"
    source = Image.open(SOURCE)
    assert source.mode == "RGBA" and source.size == (1269, 1240)
    return source


def foreground(source: Image.Image, edge: int, width: int) -> Image.Image:
    height = round(source.height * width / source.width)
    scaled = source.resize((width, height), FILTER)
    layer = Image.new("RGBA", (edge, edge))
    layer.alpha_composite(scaled, ((edge - width) // 2, (edge - height) // 2))
    return layer


def mask(edge: int, shape: str) -> Image.Image:
    # Supersample only the platform-like outer mask, not the user's artwork.
    scale = 4
    size = edge * scale
    image = Image.new("L", (size, size))
    draw = ImageDraw.Draw(image)
    box = (0, 0, size - 1, size - 1)
    if shape == "circle":
        draw.ellipse(box, fill=255)
    elif shape == "square":
        draw.rectangle(box, fill=255)
    elif shape == "rounded-square":
        draw.rounded_rectangle(box, radius=round(size * 0.22), fill=255)
    elif shape == "squircle":
        pixels = image.load()
        half = size / 2
        for y in range(size):
            for x in range(size):
                if abs((x + 0.5 - half) / half) ** 4 + abs((y + 0.5 - half) / half) ** 4 <= 1:
                    pixels[x, y] = 255
    else:
        raise ValueError(shape)
    return image.resize((edge, edge), FILTER)


def legacy(source: Image.Image, edge: int, round_icon: bool) -> Image.Image:
    layer = foreground(source, edge * 4, round(edge * 4 * 40 / 48))
    image = Image.new("RGBA", layer.size, BACKGROUND)
    image.alpha_composite(layer)
    image = image.resize((edge, edge), FILTER)
    image.putalpha(mask(edge, "circle" if round_icon else "rounded-square"))
    return image


def png(image: Image.Image) -> bytes:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=False, compress_level=9)
    return output.getvalue()


def assets(source: Image.Image) -> dict[Path, Image.Image]:
    result = {}
    for density, scale in DENSITIES.items():
        result[RES / f"drawable-{density}/ic_launcher_foreground.png"] = foreground(
            source, round(108 * scale), round(60 * scale))
        for name, is_round in [("ic_launcher", False), ("ic_launcher_round", True)]:
            result[RES / f"mipmap-{density}/{name}.png"] = legacy(source, round(48 * scale), is_round)
    return result


def previews(source: Image.Image, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    # Use the actual xxxhdpi foreground, then crop its central 72dp viewport.
    layer = Image.open(RES / "drawable-xxxhdpi/ic_launcher_foreground.png")
    full = Image.new("RGBA", layer.size, BACKGROUND)
    full.alpha_composite(layer)
    visible = full.crop((72, 72, 360, 360))
    sheet = Image.new("RGB", (1280, 760), "#eef2f8")
    draw = ImageDraw.Draw(sheet)
    for row, bg in enumerate(["#eef2f8", "#070c14"]):
        draw.rectangle((0, row * 380, 1280, (row + 1) * 380), fill=bg)
        for column, shape in enumerate(["circle", "squircle", "rounded-square", "square"]):
            rendered = visible.copy()
            rendered.putalpha(mask(288, shape))
            rendered.save(output / f"adaptive-{shape}.png")
            x, y = 16 + column * 320, row * 380 + 30
            sheet.paste(rendered, (x, y), rendered)
            draw.text((x, y + 305), shape, fill="#39748c" if row == 0 else "#b9d5e2")
    sheet.save(output / "adaptive-mask-contact-sheet.png")
    density_sheet = Image.new("RGB", (1100, 520), "#eef2f8")
    draw = ImageDraw.Draw(density_sheet)
    x = 20
    for density, scale in DENSITIES.items():
        edge = round(48 * scale)
        for row, name in enumerate(["ic_launcher", "ic_launcher_round"]):
            im = Image.open(RES / f"mipmap-{density}/{name}.png")
            density_sheet.paste(im, (x, 40 + row * 250), im)
            draw.text((x, 25 + row * 250), f"{density} {edge}px", fill="#111827")
        x += 220
    density_sheet.save(output / "legacy-density-contact-sheet.png")
    # A host-only illustration, not a claim of a captured physical system splash.
    splash = Image.new("RGB", (800, 500), "#ffffff")
    splash.paste(visible.resize((240, 240), FILTER), (80, 130))
    dark = Image.new("RGB", (400, 500), "#111827")
    dark.paste(visible.resize((240, 240), FILTER), (80, 130))
    splash.paste(dark, (400, 0))
    ImageDraw.Draw(splash).text((16, 16), "Host icon composition only; verify actual Android 12+ system splash", fill="#111827")
    splash.save(output / "splash-composition-preview.png")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--previews", type=Path)
    options = parser.parse_args()
    source = load_source()
    report = {"source_sha256": SOURCE_SHA256, "source_size": list(source.size), "assets": []}
    for path, image in assets(source).items():
        content = png(image)
        if options.check:
            assert path.read_bytes() == content, f"Non-reproducible asset: {path}"
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        report["assets"].append({"path": str(path.relative_to(APP)), "size": list(image.size),
                                 "sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)})
    if options.previews:
        previews(source, options.previews)
        (options.previews / "asset-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
