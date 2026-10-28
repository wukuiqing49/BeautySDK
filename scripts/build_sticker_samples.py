"""将现有原创PNG转换为SDK v1标准资源包；无需安装图像库。"""
from pathlib import Path
import json
import zipfile


def build(root: Path):
    source = root / "feature/feature_app/src/main/assets/beauty_stickers"
    destination = root / "feature/feature_app/src/main/assets/sticker_packs"
    examples = root / "doc/stickers/samples"
    destination.mkdir(parents=True, exist_ok=True)
    examples.mkdir(parents=True, exist_ok=True)
    names = {"round_glasses": "Round glasses", "heart_glasses": "Heart glasses",
             "curled_moustache": "Curled moustache", "blue_top_hat": "Blue top hat",
             "gold_crown": "Gold crown", "cat_ears": "Cat ears"}
    specs = json.loads((source / "manifest.json").read_text(encoding="utf-8"))["stickers"]
    for old in specs:
        manifest = {"schemaVersion": 1, "id": old["id"], "name": names[old["id"]],
                    "preview": "preview.png", "layers": [{"id": "main", "image": "sticker.png",
                    "anchor": "EYES" if old["anchor"] == "EYE_MIDPOINT" else "UPPER_LIP",
                    "widthInAnchorSpans": old["widthInAnchorSpans"], "pivot": old["pivot"],
                    "offset": [0, 0], "rotationDegrees": 0}]}
        text = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        png = (source / old["file"]).read_bytes()
        target = destination / (old["id"] + ".zip")
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in [("manifest.json", text), ("preview.png", png), ("sticker.png", png)]:
                entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, content)
        (examples / target.name).write_bytes(target.read_bytes())
        if old["id"] == "round_glasses":
            template = root / "doc/stickers/template"
            template.mkdir(parents=True, exist_ok=True)
            for name, content in [("manifest.json", text), ("preview.png", png), ("sticker.png", png)]:
                (template / name).write_bytes(content)
    print(f"Built {len(specs)} resource packs")


if __name__ == "__main__":
    build(Path(__file__).resolve().parents[1])
