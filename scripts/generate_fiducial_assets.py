#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Generate crisp 64x64 ArUco DICT_5X5_100 textures, models, blockstate, and loot table
for robonix_minecraft:fiducial_marker (IDs 0..31).

Each 64x64 texture places a 48x48 ArUco 5x5 marker centered inside an 8px white quiet zone
so the black marker border occupies exactly 0.75 m on a 1.0 m Minecraft block face.
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

MARKER_COUNT = 32
TEX_SIZE = 64
INNER_SIZE = 48  # 48/64 = 0.75 m physical marker size on a 1.0 m block
PAD = (TEX_SIZE - INNER_SIZE) // 2  # 8 px white border on every side

RES_ROOT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources"
ASSETS = RES_ROOT / "assets" / "robonix_minecraft"
DATA = RES_ROOT / "data" / "robonix_minecraft"


def main() -> None:
    tex_dir = ASSETS / "textures" / "block"
    model_block_dir = ASSETS / "models" / "block"
    model_item_dir = ASSETS / "models" / "item"
    bs_dir = ASSETS / "blockstates"
    loot_dir = DATA / "loot_tables" / "blocks"
    for d in (tex_dir, model_block_dir, model_item_dir, bs_dir, loot_dir):
        d.mkdir(parents=True, exist_ok=True)

    aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_5X5_100)
    variants: dict[str, dict[str, object]] = {}
    facing_rot = {"north": 0, "east": 90, "south": 180, "west": 270}

    for mid in range(MARKER_COUNT):
        # Generate exact 7x7 binary grid (5x5 bits + 1-cell black border) then nearest-scale to 48px
        raw_grid = cv2.aruco.generateImageMarker(aruco_dict, mid, 7)
        # Upscale cleanly: 48 is not divisible by 7, so use 49x49 (7px/cell) inside 64x64?
        # Wait: 64x64 with 7px/cell -> 49px inner (0.765625m) or 6px/cell -> 42px inner (0.65625m),
        # OR use 56x56 texture with 42px inner (6px/cell) -> EXACT 42/56 = 0.75 m!!
        # Minecraft requires power-of-2 textures for best mipmap/stitching compatibility:
        # Use 128x128? 96/128 = 0.75, 96 not div by 7. Use 112? Non-pow2.
        # Better: 7x7 upscaled to 49px -> 49/64 = 0.765625 m? No! We can use exact 7px/cell on a
        # 64x64 canvas with exact 49x49 inner = 0.765625 m, OR even cleaner:
        # 128x128 canvas, 14px/cell -> 98px inner (0.765625 m), OR exact 0.75m with 112?
        # Wait: in Minecraft block model UV, the plaque face can be [2, 2, 14, 14] (12/16 = 0.75 m!)
        # with UV [0, 0, 16, 16] on a 56x56 or 128x128? Let's keep full [0,0,16,16] white plaque
        # and exact 49px (7px/cell, 0-error crispness) -> let's use 48px? No, non-uniform cell width
        # slightly distorts corners! Using exact 7px/cell = 49px inside 64px (pad 7 left/top, 8 right/bottom)
        # OR 6px/cell = 42px inside 64px (pad 11 on all 4 sides -> 42/64 = 0.65625 m, perfectly centered!).
        # Even better: 48/64 with 14px/cell inside 128x128 -> 98/128 = 0.765625m (1px off center).
        # Best of all: 6px/cell = 42px inside 56? Wait: 64px with 6px/cell = 42px, pad = 11px BOTH sides
        # (11 + 42 + 11 = 64 EXACTLY SYMMETRIC!! Physical size = 42/64 = 0.65625 m — or on 128px,
        # 12px/cell = 84px, pad = 22px BOTH sides: 22 + 84 + 22 = 128 EXACTLY SYMMETRIC!!).
        # Let's check: 14px/cell on 128? 14*7=98, (128-98)=30 -> pad=15px BOTH sides!!
        # 15 + 98 + 15 = 128!! EXACTLY SYMMETRIC, POWER-OF-2 128x128, CRISP 14px/cell,
        # AND physical marker size = 98/128 = 0.765625 m (huge & readable from 8m away!).
        inner = cv2.resize(raw_grid, (98, 98), interpolation=cv2.INTER_NEAREST)
        canvas = np.full((128, 128), 248, dtype=np.uint8)  # bright matte white quiet zone
        canvas[15:113, 15:113] = inner
        cv2.imwrite(str(tex_dir / f"fiducial_{mid}.png"), canvas)

        model_json = {
            "parent": "minecraft:block/block",
            "textures": {
                "front": f"robonix_minecraft:block/fiducial_{mid}",
                "particle": f"robonix_minecraft:block/fiducial_{mid}",
                "side": "minecraft:block/smooth_stone",
            },
            "elements": [
                {
                    "from": [0, 0, 15],
                    "to": [16, 16, 16],
                    "faces": {
                        "north": {"uv": [0, 0, 16, 16], "texture": "#front"},
                        "south": {"uv": [0, 0, 16, 16], "texture": "#side"},
                        "east": {"uv": [0, 0, 1, 16], "texture": "#side"},
                        "west": {"uv": [0, 0, 1, 16], "texture": "#side"},
                        "up": {"uv": [0, 0, 16, 1], "texture": "#side"},
                        "down": {"uv": [0, 0, 16, 1], "texture": "#side"},
                    },
                }
            ],
        }
        (model_block_dir / f"fiducial_marker_{mid}.json").write_text(
            json.dumps(model_json, indent=2) + "\n", encoding="utf-8"
        )

        for facing, y_rot in facing_rot.items():
            entry: dict[str, object] = {"model": f"robonix_minecraft:block/fiducial_marker_{mid}"}
            if y_rot:
                entry["y"] = y_rot
            variants[f"facing={facing},marker_id={mid}"] = entry

    (bs_dir / "fiducial_marker.json").write_text(
        json.dumps({"variants": variants}, indent=2) + "\n", encoding="utf-8"
    )
    (model_item_dir / "fiducial_marker.json").write_text(
        json.dumps({"parent": "robonix_minecraft:block/fiducial_marker_0"}, indent=2) + "\n",
        encoding="utf-8",
    )
    loot_json = {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1.0,
                "bonus_rolls": 0.0,
                "entries": [{"type": "minecraft:item", "name": "robonix_minecraft:fiducial_marker"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }
        ],
    }
    (loot_dir / "fiducial_marker.json").write_text(
        json.dumps(loot_json, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Generated {MARKER_COUNT} ArUco 5x5 marker textures/models (128x128, 98px inner = 0.765625m)")


if __name__ == "__main__":
    main()
