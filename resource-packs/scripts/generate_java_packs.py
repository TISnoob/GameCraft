#!/usr/bin/env python3
"""Regenerate the checked-in Java resource-pack models and pixel textures."""

from __future__ import annotations

import json
import shutil
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/packs"
REFERENCE_MODELS = ROOT.parent / "reference-models/wanna-play-chess"
THIRD_PARTY_NOTICE = ROOT.parent / "licenses/WannaPlayChess-MIT.txt"


def png(path: Path, base: tuple[int, int, int], accent: tuple[int, int, int], mark: int,
        kind: str = "") -> None:
    """Create crisp, original pixel-art textures for furniture, cards, and pieces."""
    from PIL import Image, ImageDraw

    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGBA", (64, 64), (25, 22, 21, 255))
    draw = ImageDraw.Draw(image)
    if kind == "table-wood":
        image = Image.new("RGBA", (16, 16), (76, 43, 22, 255))
        wood = ImageDraw.Draw(image)
        for y in range(16):
            shade = ((91, 53, 28), (81, 46, 24), (99, 60, 31), (72, 40, 22))[(y // 4) % 4]
            wood.line((0, y, 15, y), fill=shade)
        for y in (0, 4, 8, 12):
            wood.line((0, y, 15, y), fill=(55, 31, 17))
            for x in (3, 11):
                wood.point((x, min(y + 1, 15)), fill=(54, 31, 17))
        image.save(path)
        return
    if kind.startswith("table-"):
        game = kind[6:]
        surfaces = {
            "chess": ((44, 111, 62), (80, 145, 83)),
            "ludo": ((231, 216, 178), (175, 152, 106)),
            "chinese-checkers": ((38, 67, 102), (222, 181, 88)),
            "checkers": ((63, 103, 61), (129, 152, 93)),
            "monopoly": ((222, 208, 168), (177, 56, 48)),
            "uno": ((38, 112, 56), (65, 139, 73)),
            "solitaire": ((43, 101, 57), (95, 147, 81)),
            "sudoku": ((229, 221, 197), (92, 112, 84)),
        }
        base, accent = surfaces.get(game, (base, accent))
        wood = (86, 48, 28)
        wood_light = (137, 82, 42)
        draw.rectangle((0, 0, 63, 63), fill=wood)
        for y in range(2, 64, 5):
            draw.line((0, y, 63, y), fill=wood_light if y % 10 else (64, 36, 24), width=1)
        draw.rectangle((6, 6, 57, 57), fill=base)
        for n in range(24):
            x = (n * 19 + mark * 3) % 50 + 7
            y = (n * 31 + mark) % 50 + 7
            draw.point((x, y), fill=accent)
        if game in ("chess", "checkers"):
            for y in range(8):
                for x in range(8):
                    if (x + y) % 2 == 0:
                        draw.rectangle((8 + x * 6, 8 + y * 6, 13 + x * 6, 13 + y * 6), fill=accent)
        elif game == "ludo":
            for x, y, color in ((11, 11, (204, 49, 48)), (44, 11, (241, 194, 42)),
                                (11, 44, (61, 128, 216)), (44, 44, (70, 172, 87))):
                draw.rectangle((x, y, x + 8, y + 8), fill=color)
            draw.polygon(((26, 21), (43, 32), (32, 43), (21, 32)), fill=(239, 230, 200))
        elif game == "monopoly":
            draw.rectangle((9, 9, 54, 54), outline=(240, 229, 193), width=3)
            palette = ((204, 49, 48), (241, 194, 42), (61, 128, 216), (70, 172, 87))
            for i in range(8):
                color = palette[i % len(palette)]
                draw.rectangle((12 + i * 5, 9, 15 + i * 5, 14), fill=color)
                draw.rectangle((12 + i * 5, 49, 15 + i * 5, 54), fill=color)
                draw.rectangle((9, 13 + i * 4, 14, 16 + i * 4), fill=color)
                draw.rectangle((49, 13 + i * 4, 54, 16 + i * 4), fill=color)
        elif game == "sudoku":
            for i in range(10):
                width = 2 if i % 3 == 0 else 1
                p = 8 + i * 6
                draw.line((10, p, 54, p), fill=accent, width=width)
                draw.line((p, 10, p, 54), fill=accent, width=width)
        elif game == "chinese-checkers":
            for y in range(5):
                for x in range(5):
                    draw.ellipse((13 + x * 8, 13 + y * 8, 17 + x * 8, 17 + y * 8), fill=accent)
        image.save(path)
        return
    if kind.startswith("chess-"):
        draw.rectangle((0, 0, 63, 63), fill=base)
        dark_piece = "black" in kind
        shade = (28, 25, 26) if dark_piece else (255, 248, 222)
        highlight = (104, 96, 91) if dark_piece else (210, 189, 143)
        draw.line((5, 7, 58, 7), fill=highlight, width=3)
        draw.line((6, 12, 57, 12), fill=shade, width=2)
        for y in range(17, 61, 8):
            draw.line((10, y, 53, y), fill=highlight if y % 16 else shade, width=1)
        if "bishop" in kind:
            draw.line((30, 15, 38, 7), fill=shade, width=3)
        elif "king" in kind:
            draw.rectangle((29, 5, 35, 10), fill=highlight)
            draw.rectangle((25, 7, 39, 9), fill=highlight)
        elif "queen" in kind:
            for x in (12, 23, 32, 41, 52):
                draw.ellipse((x - 2, 5, x + 2, 9), fill=highlight)
        elif "rook" in kind:
            for x in (13, 25, 38, 50):
                draw.rectangle((x - 2, 3, x + 2, 9), fill=highlight)
        elif "knight" in kind:
            draw.ellipse((25, 5, 43, 18), fill=highlight)
            draw.polygon(((25, 13), (17, 4), (23, 5), (32, 12)), fill=shade)
        else:
            draw.ellipse((25, 5, 39, 19), fill=highlight)
        image.save(path)
        return
    if kind in ("chess-move-marker", "chess-selected-marker"):
        image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        if kind == "chess-move-marker":
            draw.ellipse((6, 6, 58, 58), fill=(76, 220, 104, 220), outline=(236, 255, 235, 245), width=4)
            draw.ellipse((20, 20, 44, 44), fill=(35, 154, 69, 220))
        else:
            draw.ellipse((4, 4, 60, 60), fill=(251, 194, 42, 245), outline=(255, 248, 194, 255), width=4)
            draw.ellipse((13, 13, 51, 51), fill=(0, 0, 0, 0), outline=(135, 88, 13, 235), width=4)
        image.save(path)
        return
    if kind.startswith("chair-"):
        # Upholstery color per game, with deep walnut edging.
        draw.rectangle((4, 5, 59, 59), fill=(62, 39, 28))
        draw.rectangle((9, 9, 54, 48), fill=base)
        draw.rectangle((11, 11, 52, 15), fill=accent)
        draw.rectangle((11, 20, 52, 43), fill=base)
        draw.rectangle((8, 49, 15, 62), fill=(75, 47, 31))
        draw.rectangle((48, 49, 55, 62), fill=(75, 47, 31))
        image.save(path)
        return
    if kind.startswith("uno-card-"):
        card = int(kind.rsplit("-", 1)[1])
        colors = ((210, 45, 50), (234, 189, 35), (52, 151, 75), (52, 103, 200))
        if card < 52:
            card_color = colors[card // 13]
            rank = card % 13
            symbol = str(rank) if rank < 10 else ("S" if rank == 10 else "R" if rank == 11 else "+2")
        else:
            card_color = (35, 33, 34)
            rank = card - 52
            symbol = "W" if rank == 0 else "+4"
        draw.rounded_rectangle((7, 3, 57, 61), radius=8, fill=(15, 14, 15))
        draw.rounded_rectangle((9, 5, 55, 59), radius=7, fill=(246, 239, 215))
        if card < 52:
            draw.rounded_rectangle((12, 8, 52, 56), radius=7, fill=card_color)
            draw.ellipse((14, 14, 50, 50), fill=(248, 241, 217), outline=(255, 255, 240), width=1)
            draw.ellipse((17, 17, 47, 47), fill=card_color)
        else:
            for i, color in enumerate(colors):
                draw.pieslice((12, 8, 52, 52), start=i * 90, end=(i + 1) * 90 - 8, fill=color)
        # Large white center pip/rank. The corner labels keep the tile readable from above.
        draw.text((28, 25), symbol, anchor="mm", fill=(255, 255, 242), stroke_width=1, stroke_fill=(26, 24, 24))
        draw.text((16, 9), symbol[:2], anchor="mm", fill=(255, 255, 245), stroke_width=1, stroke_fill=(25, 25, 25))
        image.save(path)
        return
    if kind in ("uno-call", "uno-pass"):
        label = "UNO" if kind == "uno-call" else "OK"
        draw.rounded_rectangle((4, 8, 60, 56), radius=12, fill=(25, 23, 23))
        draw.ellipse((9, 13, 55, 51), fill=(205, 39, 43))
        draw.text((32, 32), label, anchor="mm", fill=(255, 240, 219), stroke_width=1, stroke_fill=(30, 25, 23))
        image.save(path)
        return
    if kind.startswith("solitaire-card-"):
        card = int(kind.rsplit("-", 1)[1])
        suit = card // 13
        rank = card % 13
        rank_text = ("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")[rank]
        ink = (189, 31, 44) if suit < 2 else (27, 31, 35)
        draw.rounded_rectangle((5, 3, 59, 61), radius=5, fill=(22, 20, 20))
        draw.rounded_rectangle((7, 5, 57, 59), radius=4, fill=(250, 248, 231))
        draw.text((13, 13), rank_text, anchor="mm", fill=ink)
        draw_suit(draw, suit, 13, 25, ink, 8)
        draw_suit(draw, suit, 32, 37, ink, 14)
        draw.text((51, 51), rank_text, anchor="mm", fill=ink)
        image.save(path)
        return
    if kind.startswith("sudoku-"):
        digit = int(kind.rsplit("-", 1)[1])
        glyphs = {
            1: ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            2: ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            3: ("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            4: ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            5: ("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
            6: ("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
            7: ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            8: ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            9: ("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
        }
        image = Image.new("RGBA", (64, 64), (239, 232, 204, 255))
        draw = ImageDraw.Draw(image)
        draw.rectangle((2, 2, 61, 61), outline=(51, 72, 54), width=3)
        draw.rectangle((5, 5, 58, 58), outline=(157, 132, 82), width=1)
        for row, bits in enumerate(glyphs[digit]):
            for column, bit in enumerate(bits):
                if bit == "1":
                    x, y = 12 + column * 8, 4 + row * 8
                    draw.rectangle((x, y, x + 6, y + 6), fill=(39, 53, 47))
        image.save(path)
        return
    if kind == "uno-draw-pile":
        draw.rounded_rectangle((5, 4, 59, 60), radius=8, fill=(20, 18, 18))
        for i, color in enumerate(((213, 49, 49), (235, 193, 36), (52, 151, 75), (52, 103, 200))):
            draw.pieslice((10 + i, 9 + i, 54 - i, 55 - i), start=i * 90, end=(i + 1) * 90 - 4, fill=color)
        draw.ellipse((21, 22, 43, 42), fill=(248, 241, 217))
        draw.text((32, 32), "GC", anchor="mm", fill=(28, 24, 24))
        image.save(path)
        return
    # Smaller piece textures use a clean material with a shaded edge and a centered insignia.
    draw.rectangle((2, 2, 61, 61), fill=base)
    draw.rectangle((5, 5, 58, 58), outline=(30, 27, 25), width=3)
    draw.rectangle((9, 9, 54, 54), outline=accent, width=2)
    for y in range(12, 54, 8):
        draw.line((11, y, 52, y), fill=accent, width=1)
    image.save(path)


def draw_suit(draw, suit: int, center_x: int, center_y: int, color: tuple[int, int, int], size: int) -> None:
    half = size // 2
    if suit == 0:  # heart
        draw.ellipse((center_x - half, center_y - half, center_x, center_y + 1), fill=color)
        draw.ellipse((center_x, center_y - half, center_x + half, center_y + 1), fill=color)
        draw.polygon(((center_x - half, center_y - 1), (center_x + half, center_y - 1),
                      (center_x, center_y + half + 2)), fill=color)
    elif suit == 1:  # diamond
        draw.polygon(((center_x, center_y - half - 1), (center_x + half, center_y),
                      (center_x, center_y + half + 1), (center_x - half, center_y)), fill=color)
    elif suit == 2:  # club
        draw.ellipse((center_x - half, center_y - half, center_x, center_y + 1), fill=color)
        draw.ellipse((center_x, center_y - half, center_x + half, center_y + 1), fill=color)
        draw.ellipse((center_x - half // 2, center_y - half, center_x + half // 2, center_y), fill=color)
        draw.polygon(((center_x - 2, center_y), (center_x + 2, center_y),
                      (center_x + 3, center_y + half + 2), (center_x - 3, center_y + half + 2)), fill=color)
    else:  # spade
        draw.ellipse((center_x - half, center_y - half, center_x, center_y + 1), fill=color)
        draw.ellipse((center_x, center_y - half, center_x + half, center_y + 1), fill=color)
        draw.polygon(((center_x - half, center_y), (center_x + half, center_y),
                      (center_x, center_y - half - 3)), fill=color)
        draw.polygon(((center_x - 2, center_y), (center_x + 2, center_y),
                      (center_x + 3, center_y + half + 3), (center_x - 3, center_y + half + 3)), fill=color)


def cube(x1: int, y1: int, z1: int, x2: int, y2: int, z2: int) -> dict:
    face = {"texture": "#piece"}
    faces = {direction: dict(face) for direction in ("north", "south", "east", "west", "up", "down")}
    return {"from": [x1, y1, z1], "to": [x2, y2, z2], "faces": faces}


def geometry(kind: str) -> list[dict]:
    if kind == "table-leg":
        return [cube(4, 0, 4, 12, 16, 12)]
    if kind.startswith("uno-card-") or kind.startswith("solitaire-card-") or kind in ("uno-call", "uno-pass"):
        return [cube(2, 0, 1, 14, 1, 15)]
    if kind.startswith("table-"):
        return [cube(0, 0, 0, 16, 8, 16)]
    if kind.startswith("chair-"):
        return [cube(2, 8, 2, 14, 11, 14), cube(3, 11, 3, 13, 16, 13),
                cube(3, 0, 3, 5, 8, 5), cube(11, 0, 3, 13, 8, 5),
                cube(3, 0, 11, 5, 8, 13), cube(11, 0, 11, 13, 8, 13)]
    if kind == "chess-move-marker":
        return [cube(2, 0, 2, 14, 1, 14)]
    if kind == "chess-selected-marker":
        return [cube(1, 0, 1, 15, 1, 3), cube(1, 0, 13, 15, 1, 15),
                cube(1, 0, 3, 3, 1, 13), cube(13, 0, 3, 15, 1, 13)]
    if kind.startswith("chess-"):
        rank = kind.rsplit("-", 1)[1]
        # Layered silhouettes read as carved tournament pieces from all camera angles.
        elements = [cube(2, 0, 2, 14, 2, 14), cube(3, 2, 3, 13, 4, 13),
                    cube(5, 4, 5, 11, 7, 11), cube(6, 7, 6, 10, 11, 10)]
        if rank == "pawn":
            return elements + [cube(5, 10, 5, 11, 12, 11), cube(4, 12, 4, 12, 15, 12),
                               cube(5, 15, 5, 11, 16, 11)]
        if rank == "knight":
            return elements + [cube(4, 10, 5, 12, 13, 11), cube(6, 12, 5, 12, 16, 10),
                               cube(5, 13, 4, 7, 15, 7), cube(9, 15, 5, 11, 16, 8),
                               cube(3, 8, 7, 6, 10, 9)]
        if rank == "bishop":
            return elements + [cube(4, 11, 4, 12, 13, 12), cube(5, 13, 5, 11, 15, 11),
                               cube(6, 15, 6, 10, 16, 10)]
        if rank == "rook":
            return elements + [cube(4, 11, 4, 12, 15, 12), cube(3, 14, 3, 6, 16, 13),
                               cube(10, 14, 3, 13, 16, 13), cube(6, 14, 6, 10, 16, 10)]
        if rank == "queen":
            return elements + [cube(4, 11, 4, 12, 14, 12), cube(4, 14, 4, 12, 15, 12),
                               cube(2, 15, 2, 5, 16, 5), cube(6, 15, 2, 9, 16, 5),
                               cube(11, 15, 2, 14, 16, 5), cube(2, 15, 11, 5, 16, 14),
                               cube(6, 15, 11, 9, 16, 14), cube(11, 15, 11, 14, 16, 14)]
        # Crowned king with a clear cross finial.
        return elements + [cube(4, 11, 4, 12, 14, 12), cube(6, 14, 6, 10, 16, 10),
                           cube(7, 15, 7, 9, 16, 15), cube(4, 15, 7, 12, 16, 9)]
    if kind.startswith("checkers-"):
        return [cube(1, 0, 1, 15, 3, 15), cube(2, 3, 2, 14, 5, 14)] + ([cube(3, 5, 3, 13, 7, 13)] if kind.endswith("king") else [])
    if kind.startswith("ludo-") or kind.startswith("chinese-"):
        return [cube(4, 0, 4, 12, 3, 12), cube(5, 3, 5, 11, 10, 11), cube(6, 10, 6, 10, 16, 10)]
    if kind.startswith("monopoly-house"):
        return [cube(2, 0, 3, 14, 9, 13), cube(1, 9, 2, 8, 15, 14), cube(8, 9, 2, 15, 15, 14)]
    if kind.startswith("monopoly-hotel"):
        return [cube(1, 0, 2, 15, 15, 14), cube(3, 15, 1, 13, 16, 15)]
    if kind.startswith("monopoly"):
        return [cube(3, 0, 3, 13, 3, 13), cube(5, 3, 5, 11, 11, 11), cube(7, 11, 7, 9, 16, 9)]
    if kind.startswith("uno-") or kind.startswith("solitaire-"):
        return [cube(1, 0, 2, 15, 2, 14), cube(2, 2, 3, 14, 3, 13)]
    if kind.startswith("sudoku-"):
        return [cube(1, 0, 1, 15, 3, 15), cube(2, 3, 2, 14, 4, 14)]
    if kind == "dice":
        return [cube(2, 1, 2, 14, 13, 14)]
    return [cube(2, 0, 2, 14, 4, 14), cube(4, 4, 4, 12, 12, 12)]


def palette(index: int) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    colors = [
        ((235, 226, 205), (176, 139, 80)), ((55, 48, 45), (133, 116, 91)),
        ((188, 52, 53), (249, 176, 138)), ((45, 48, 57), (145, 145, 157)),
        ((205, 42, 51), (255, 166, 80)), ((247, 202, 57), (255, 247, 154)),
        ((56, 111, 196), (147, 208, 247)), ((54, 151, 88), (171, 226, 127)),
        ((38, 157, 177), (163, 233, 217)), ((163, 74, 179), (234, 174, 226)),
        ((249, 249, 237), (221, 60, 56)), ((62, 123, 80), (143, 203, 123)),
    ]
    if 506 <= index <= 511:
        return colors[(4, 5, 6, 7, 9, 10)[index - 506]]
    return colors[index % len(colors)]


def models() -> dict[int, str]:
    result: dict[int, str] = {}
    pieces = ("pawn", "knight", "bishop", "rook", "queen", "king")
    for index, piece in enumerate(pieces):
        result[100 + index] = f"chess-white-{piece}"
        result[106 + index] = f"chess-black-{piece}"
    result[902] = "chess-move-marker"
    result[903] = "chess-selected-marker"
    for value, name in enumerate(("red-man", "red-king", "black-man", "black-king"), 201):
        result[value] = f"checkers-{name}"
    for value, color in enumerate(("red", "yellow", "blue", "green"), 301):
        result[value] = f"ludo-{color}"
    for value, color in enumerate(("red", "yellow", "blue", "green", "purple", "orange"), 401):
        result[value] = f"chinese-{color}"
    result[501] = "monopoly-house"
    result[505] = "monopoly-hotel"
    for value, color in enumerate(("red", "yellow", "blue", "green", "purple", "white"), 506):
        result[value] = f"monopoly-token-{color}"
    result[601] = "uno-draw-pile"
    for value in range(602, 656):
        result[value] = f"uno-card-{value - 602}"
    result[658] = "uno-call"
    result[659] = "uno-pass"
    result[701] = "solitaire-deck"
    for value in range(702, 754):
        result[value] = f"solitaire-card-{value - 702}"
    for value in range(801, 810):
        result[value] = f"sudoku-{value - 800}"
    result[900] = "gamecraft-control"
    result[901] = "dice"
    for value, game in enumerate(("chess", "ludo", "chinese-checkers", "checkers", "monopoly", "uno", "solitaire", "sudoku"), 910):
        result[value] = f"table-{game}"
        result[920 + value - 910] = f"chair-{game}"
    result[660] = "table-wood"
    return result


def game_model_ids() -> dict[str, list[int]]:
    games = {
        "chess": list(range(100, 112)) + [902, 903],
        "checkers": list(range(201, 205)),
        "ludo": list(range(301, 305)) + [901],
        "chinese-checkers": list(range(401, 407)),
        "monopoly": [501, 505, *range(506, 512), 901],
        "uno": [601, *range(602, 656), 658, 659],
        "solitaire": [701, *range(702, 754)],
        "sudoku": list(range(801, 810)),
    }
    game_index = {name: index for index, name in enumerate(games)}
    ids = models()
    for name, index in game_index.items():
        games[name].extend((900, 910 + index, 920 + index))
        games[name].extend(model_id for model_id, asset in ids.items()
                           if asset == f"table-{name}" or asset == f"chair-{name}")
    return games


def item_model(name: str) -> dict:
    if name.startswith("chess-") and name not in ("chess-move-marker", "chess-selected-marker"):
        _, side, piece = name.split("-", 2)
        model = json.loads((REFERENCE_MODELS / f"{side}_{piece}.json").read_text())
        model["textures"] = {
            key: value if ":" in value else f"minecraft:{value}"
            for key, value in model["textures"].items()
        }
        model["display"] = {
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        }
        return model

    textures = {"particle": f"gamecraft:item/{name}", "piece": f"gamecraft:item/{name}"}
    elements = geometry(name)
    if name.startswith("table-") and name != "table-wood":
        textures.update({"board": f"gamecraft:item/{name}", "side": "gamecraft:item/table-wood"})
        for face in ("north", "south", "east", "west", "down"):
            elements[0]["faces"][face]["texture"] = "#side"
        elements[0]["faces"]["up"]["texture"] = "#board"
    return {
        "textures": textures,
        "elements": elements,
        "display": {
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }


def write_pack(folder: Path, modern: bool) -> None:
    if folder.exists():
        shutil.rmtree(folder)
    (folder / "assets/gamecraft/lang").mkdir(parents=True, exist_ok=True)
    (folder / "assets/gamecraft/lang/en_us.json").write_text(json.dumps({"item.gamecraft.piece": "GameCraft piece"}, indent=2) + "\n")
    pack = {
        "pack_format": 46 if modern else 15,
        "supported_formats": {"min_inclusive": 46 if modern else 15, "max_inclusive": 57},
        "description": "GameCraft 3D boards and tabletop pieces",
    }
    if modern:
        # 1.21.9+ requires version pairs; 1.21.4–1.21.11 still use the old range field.
        pack["min_format"] = [46, 0]
        pack["max_format"] = [97, 1]
    else:
        pack["min_format"] = 15
        pack["max_format"] = 57
    (folder / "pack.mcmeta").write_text(json.dumps({"pack": pack}, indent=2) + "\n")
    ids = models()
    entries = []
    for model_id, name in sorted(ids.items()):
        model_path = folder / f"assets/gamecraft/models/item/{name}.json"
        model_path.parent.mkdir(parents=True, exist_ok=True)
        model_path.write_text(json.dumps(item_model(name), separators=(",", ":")) + "\n")
        base, accent = palette(model_id)
        if not name.startswith("chess-") or name in ("chess-move-marker", "chess-selected-marker"):
            png(folder / f"assets/gamecraft/textures/item/{name}.png", base, accent, model_id, name)
        entries.append((model_id, name))

    if THIRD_PARTY_NOTICE.exists():
        notice_destination = folder / "LICENSES/WannaPlayChess-MIT.txt"
        notice_destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(THIRD_PARTY_NOTICE, notice_destination)

    # The client renderer also uses these shared block models, which do not need item CMD ids.
    for name in ("table-leg", "table-wood"):
        model_path = folder / f"assets/gamecraft/models/item/{name}.json"
        model_path.parent.mkdir(parents=True, exist_ok=True)
        model_path.write_text(json.dumps(item_model(name), separators=(",", ":")) + "\n")
        base, accent = (86, 48, 28), (137, 82, 42)
        png(folder / f"assets/gamecraft/textures/item/{name}.png", base, accent, 660, name)

    if modern:
        item_definition = {
            "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "entries": [
                    {"threshold": model_id, "model": {"type": "minecraft:model", "model": f"gamecraft:item/{name}"}}
                    for model_id, name in entries
                ],
                "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
            }
        }
        target = folder / "assets/minecraft/items/paper.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(item_definition, indent=2) + "\n")
    else:
        overrides = [
            {"predicate": {"custom_model_data": model_id}, "model": f"gamecraft:item/{name}"}
            for model_id, name in entries
        ]
        target = folder / "assets/minecraft/models/item/paper.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({
            "parent": "minecraft:item/generated",
            "textures": {"layer0": "minecraft:item/paper"},
            "overrides": overrides,
        }, indent=2) + "\n")

    write_game_packs(folder, modern, ids)


def write_universal_pack() -> None:
    """Combines legacy overrides and modern item definitions for automatic server delivery."""
    legacy = ROOT / "java-legacy"
    modern = ROOT / "java-modern"
    destination = ROOT / "java-universal"
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(legacy, destination)
    for source in modern.rglob("*"):
        if source.is_dir() or source.name == "pack.mcmeta":
            continue
        target = destination / source.relative_to(modern)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    (destination / "pack.mcmeta").write_text(json.dumps({"pack": {
        "pack_format": 15,
        "supported_formats": {"min_inclusive": 15, "max_inclusive": 57},
        "min_format": [15, 0],
        "max_format": [97, 1],
        "description": "GameCraft 3D boards and tabletop pieces (Java 1.20–26.3)",
    }}, indent=2) + "\n")

    # Keep the Fabric client renderer self-contained; players do not need a second pack download.
    client_assets = ROOT.parents[3] / "client-mods/fabric-1.21.1/src/main/resources/assets/gamecraft"
    if client_assets.exists():
        shutil.rmtree(client_assets)
    shutil.copytree(destination / "assets/gamecraft", client_assets)


def write_game_packs(source: Path, modern: bool, ids: dict[int, str]) -> None:
    variant = "modern" if modern else "legacy"
    all_ids = game_model_ids()
    for game, selected_ids in all_ids.items():
        destination = ROOT / "game-specific" / variant / game
        if destination.exists():
            shutil.rmtree(destination)
        (destination / "assets/gamecraft/lang").mkdir(parents=True, exist_ok=True)
        (destination / "assets/minecraft").mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / "pack.mcmeta", destination / "pack.mcmeta")
        shutil.copy2(source / "assets/gamecraft/lang/en_us.json",
                     destination / "assets/gamecraft/lang/en_us.json")
        entries = []
        for model_id in sorted(set(selected_ids)):
            name = ids[model_id]
            for category in ("models/item", "textures/item"):
                extension = "json" if category.startswith("models") else "png"
                rel = Path(f"assets/gamecraft/{category}/{name}.{extension}")
                if (source / rel).is_file():
                    (destination / rel).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source / rel, destination / rel)
            entries.append((model_id, name))
        if THIRD_PARTY_NOTICE.exists() and game == "chess":
            notice_destination = destination / "LICENSES/WannaPlayChess-MIT.txt"
            notice_destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(THIRD_PARTY_NOTICE, notice_destination)
        if modern:
            paper_definition = {
                "model": {
                    "type": "minecraft:range_dispatch",
                    "property": "minecraft:custom_model_data",
                    "index": 0,
                    "entries": [
                        {"threshold": model_id, "model": {"type": "minecraft:model", "model": f"gamecraft:item/{name}"}}
                        for model_id, name in entries
                    ],
                    "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
                }
            }
            target = destination / "assets/minecraft/items/paper.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(paper_definition, indent=2) + "\n")
        else:
            paper_definition = {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "minecraft:item/paper"},
                "overrides": [
                    {"predicate": {"custom_model_data": model_id}, "model": f"gamecraft:item/{name}"}
                    for model_id, name in entries
                ],
            }
            target = destination / "assets/minecraft/models/item/paper.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(paper_definition, indent=2) + "\n")


write_pack(ROOT / "java-legacy", modern=False)
write_pack(ROOT / "java-modern", modern=True)
write_universal_pack()
