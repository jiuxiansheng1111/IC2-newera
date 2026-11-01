"""Create R19/R20 smile comparison boards and provenance records."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(r"C:\Users\罗斌\Desktop\IC2-newera")
WORK = ROOT / "art/robot-mcp-workspace/characters/head-r20-smile"
R19 = ROOT / "art/robot-mcp-workspace/characters/head-r19"
REVIEW = WORK / "review"
PEOPLE = {
    "anon": ("爱音", "412b99aa3c39a0a7", "67effc4fb913e425", "873878c9-892b-48e8-aedb-7e571258b60e", "2c69c45e-6ace-443b-b7e3-d1559747b2bb", "71ac7242-4107-45fa-8135-19373865eb84"),
    "tomori": ("灯", "4970ec77bad4a5db", "2966eeec5ca7a0f7", "68d389fe-ce4c-41f0-8c33-0c67a03dc6e0", "1f794b1d-5ee3-459b-bf30-d4a4e3489100", "b406d127-0e03-442d-88fc-8514145ffa3b"),
    "soyo": ("爽世", "3c5a48df6d508734", "6c49808629ef0920", "749867b0-62b5-4cfe-9ccc-454cb50e3dec", "76f30bb6-5343-4cad-93a1-c710d96933a3", "fbe3850e-2f2c-450e-8e2c-214b607b9537"),
    "taki": ("立希", "d57d3265a29609f1", "48fa267537e2f377", "9a851215-38ec-4b9e-8b56-0bd3827abf09", "f5cd5f52-f257-4369-9ba6-274bac6baf71", "43b20202-f84f-4842-9f3c-7a1c46be10ee"),
    "rana": ("乐奈", "0012983ffbee4f69", "bbacccacc734ff26", "01d0df42-cfa9-4196-9dd1-1b542e44e6d6", "ee4e1478-e5e4-4a7a-88a0-8257f114c9ac", "45ae6bb4-1a58-47bf-9710-84b0c0ac2f02"),
    "sakiko": ("祥子", "867ad9f45fb3fa02", "81a8b8b6209208b0", "0eafb3b5-18d6-4eb2-bdbe-e6aceb91fc11", "7abbf775-bd5b-4130-a4dd-bb99b92882b9", "5228e33b-178c-4d69-8d80-cc1c41bc79d0"),
    "uika": ("初华", "cf316dc5e78e1eda", "c8020b12fb44425f", "fba6d8a5-f278-4b0e-ac7d-9c09a7524a83", "1d420147-e2a3-4423-91c3-0c254ca78770", "51250fb8-f05a-4d2a-ac2c-0f7acaadbfc6"),
    "umiri": ("海铃", "c8d1ff7da2aa703d", "5adad1248457e44a", "4616eacc-aaae-4f71-bf79-98731752632e", "3ec169b1-65b3-4a2c-9dd8-62f83df70987", "29e40ca5-d797-48a9-88dc-a6648ecc46a9"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def non_mouth_elements(path: Path) -> list[dict]:
    document = read_json(path)
    return sorted(
        (element for element in document.get("elements", [])
         if not any(word in element.get("name", "").lower() for word in ("mouth", "smile"))),
        key=lambda element: element["uuid"],
    )


def image_path(character: str, variant: str, view: str, revision: str) -> Path:
    return REVIEW / f"{character}-{variant}-{view}-{revision[:8]}.png"


def crop_face(source: Path) -> Image.Image:
    with Image.open(source) as image:
        return image.convert("RGB").crop((225, 8, 475, 258)).resize((230, 230), Image.Resampling.LANCZOS)


def font(size: int = 16):
    try:
        return ImageFont.truetype("msyh.ttc", size)
    except OSError:
        return ImageFont.load_default()


def make_eight_person_board(view: str) -> Path:
    cell = 230
    label_h, gap, columns = 28, 12, 4
    rows = 2
    board = Image.new("RGB", (columns * cell + (columns + 1) * gap,
                               rows * (cell + label_h) + (rows + 1) * gap), "#25272c")
    draw = ImageDraw.Draw(board)
    for index, (key, (name, r19_rev, r20_rev, *_)) in enumerate(PEOPLE.items()):
        x = gap + (index % columns) * (cell + gap)
        y = gap + (index // columns) * (cell + label_h + gap)
        source = image_path(key, "r20", view, r20_rev)
        board.paste(crop_face(source), (x, y + label_h))
        draw.text((x + 3, y + 4), f"{name} · R20微笑 · {view}", fill="white", font=font(15))
    target = REVIEW / f"all-eight-r20-{view}-candidate.png"
    board.save(target)
    return target


def make_six_comparison_board() -> Path:
    chars = list(PEOPLE.items())[2:]
    cell, label_h, gap = 190, 25, 10
    columns = 4  # front R19/R20, three-quarter R19/R20
    row_h = cell + label_h
    board = Image.new("RGB", (columns * cell + (columns + 1) * gap,
                               len(chars) * row_h + (len(chars) + 1) * gap), "#25272c")
    draw = ImageDraw.Draw(board)
    for row, (key, (name, r19_rev, r20_rev, *_)) in enumerate(chars):
        y = gap + row * (row_h + gap)
        labels = ["正面 R19", "正面 R20", "斜视 R19", "斜视 R20"]
        paths = [image_path(key, "r19", "front", r19_rev), image_path(key, "r20", "front", r20_rev),
                 image_path(key, "r19", "three-quarter", r19_rev), image_path(key, "r20", "three-quarter", r20_rev)]
        for col, (label, path) in enumerate(zip(labels, paths)):
            x = gap + col * (cell + gap)
            crop = crop_face(path).resize((cell, cell), Image.Resampling.LANCZOS)
            board.paste(crop, (x, y + label_h))
            draw.text((x + 3, y + 4), f"{name} · {label}", fill="white", font=font(14))
    target = REVIEW / "six-r19-r20-smile-comparisons.png"
    board.save(target)
    return target


def main() -> None:
    receipts = {"scope": "static R20 smile candidate; no runtime or R16/R19 model files changed", "characters": {}}
    mouth_ids = {"characters": {}}
    for key, (name, r19_revision, r20_revision, original_uuid, left_uuid, right_uuid) in PEOPLE.items():
        source = R19 / key / f"{key}-head-r19.bbmodel"
        model = WORK / key / f"{key}-head-r20-smile.bbmodel"
        renders = {}
        for variant, revision in (("r19", r19_revision), ("r20", r20_revision)):
            for view in ("front", "three-quarter"):
                path = image_path(key, variant, view, revision)
                if not path.is_file():
                    raise FileNotFoundError(path)
                renders[f"{variant}_{view}"] = {"returned_path": str(path), "sha256": sha256(path)}
        if not source.is_file() or not model.is_file():
            raise FileNotFoundError(source if not source.is_file() else model)
        unchanged = non_mouth_elements(source) == non_mouth_elements(model)
        original = read_json(source)
        neutral_mouth = next(element for element in original["elements"] if element["uuid"] == original_uuid)
        mouth_ids["characters"][key] = {
            "display_name_zh": name,
            "r19_revision": r19_revision,
            "original_neutral_mouth_uuid": original_uuid,
            "original_neutral_mouth_name": neutral_mouth["name"],
            "r20_center_stroke_uuid": original_uuid,
            "r20_added_smile_left_uuid": left_uuid,
            "r20_added_smile_right_uuid": right_uuid,
            "parent_group": "head/smile_geometry",
        }
        receipts["characters"][key] = {
            "display_name_zh": name,
            "r19_model": str(source),
            "r19_sha256": sha256(source),
            "r19_revision": r19_revision,
            "r20_model": str(model),
            "r20_sha256": sha256(model),
            "r20_revision": r20_revision,
            "non_mouth_elements_identical": unchanged,
            "mcp_render_receipts": renders,
        }
    receipts["comparison_boards"] = {
        "front": str(make_eight_person_board("front")),
        "three_quarter": str(make_eight_person_board("three-quarter")),
        "six_character_r19_r20": str(make_six_comparison_board()),
    }
    receipts["review_note"] = (
        "Root reviewer accepted the restrained closed-mouth smile direction on Anon and Tomori. "
        "The six added candidates follow that direction with small per-character width and upturn differences. "
        "Static geometry only; root review of the six additions and runtime integration remain pending."
    )
    (REVIEW / "render-receipts.json").write_text(json.dumps(receipts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (REVIEW / "mouth-element-uuids.json").write_text(json.dumps(mouth_ids, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
