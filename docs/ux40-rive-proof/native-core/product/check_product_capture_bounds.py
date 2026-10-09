#!/usr/bin/env python3
"""Read-only capture-size/alpha-margin/distinct-pixel regression.

RIVE_PRODUCT_OUTPUT selects an existing output directory; default: ./artifacts
beside this script. These 72 snapshots cannot prove a complete motion envelope
or compact-icon legibility. This is host-only evidence, not Android acceptance.
"""
import hashlib
import json
import os
from pathlib import Path

from PIL import Image

root = Path(os.environ.get("RIVE_PRODUCT_OUTPUT", Path(__file__).parent / "artifacts")) / "captures"
boxes = []
for filename in sorted(root.glob("*.png")):
    with Image.open(filename) as image:
        assert image.size == (256, 256), (filename.name, image.size)
        box = image.convert("RGBA").getchannel("A").getbbox()
        assert box, filename.name
        margin = min(box[0], box[1], 256 - box[2], 256 - box[3])
        assert margin > 0, (filename.name, box)
        boxes.append((margin, filename.name, box))
assert len(boxes) == 72, "Expected the 72 original runtime snapshots"
distinct = {}
for mascot_id in ("nimbo", "folio"):
    hashes = []
    for filename in sorted(root.glob(f"{mascot_id}-*Reduced-181.png")):
        with Image.open(filename) as image:
            hashes.append(hashlib.sha256(image.convert("RGBA").tobytes()).hexdigest())
    assert len(hashes) == 9 and len(set(hashes)) == 9, mascot_id
    distinct[mascot_id] = 9
print(json.dumps({
    "scope": "Host snapshots only; not full motion-envelope or compact-icon acceptance",
    "captures": len(boxes), "size": [256, 256],
    "noAlphaTouchingCanvasEdges": True,
    "minimumCapturedAlphaMargin": min(boxes)[0],
    "distinctReducedPixelHashes": distinct,
    "tightestFiveCaptures": sorted(boxes)[:5], "passed": True,
}, indent=2))
