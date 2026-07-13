#!/usr/bin/env python3
"""Generate tiny original outline fonts for fallback/spacing tests.

Run: uv run --with fonttools python tools/generate-font-fixtures.py
These geometric test glyphs are released under the project's Apache-2.0 license.
"""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

ROOT = Path(__file__).resolve().parents[1] / "lib/src/test/resources/glyph-fixtures"


def font(name, cmap, advance, triangle):
    builder = FontBuilder(1000, isTTF=True)
    order = [".notdef"] + list(cmap.values())
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap(cmap)
    glyphs = {}
    for glyph in order:
        pen = TTGlyphPen(None)
        pen.moveTo((0, 0))
        pen.lineTo((advance - 80, 0))
        pen.lineTo((advance // 2 if triangle else advance - 80, 700))
        if not triangle:
            pen.lineTo((0, 700))
        pen.closePath()
        glyphs[glyph] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({glyph: (advance, 0) for glyph in order})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": name, "styleName": "Regular", "uniqueFontIdentifier": name, "fullName": name, "psName": name})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    builder.setupMaxp()
    # Stable timestamps make regeneration reproducible.
    builder.font["head"].created = builder.font["head"].modified = 3800000000
    builder.font.recalcTimestamp = False
    ROOT.mkdir(parents=True, exist_ok=True)
    builder.save(ROOT / (name + ".ttf"))


font("Primary", {ord("A"): "A", ord("M"): "M", 0x26A0: "warning"}, 600, False)
font("Fallback", {ord("A"): "A", ord("B"): "B", ord("W"): "W"}, 1100, True)
