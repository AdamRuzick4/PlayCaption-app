"""Prepare paired LG grayscale crops for the Android benchmark or score its result."""
import argparse
import json
from collections import Counter
from pathlib import Path
import re


def prepare(root, output):
    from PIL import Image
    output.mkdir(parents=True, exist_ok=False)
    cases = json.loads((root / "work/bright-ocr/manifest.json").read_text())
    rows = []
    for case in cases:
        source = Path(case["file"])
        if source.is_absolute():
            # Older LG measurements stored absolute paths to work/model-comparison.
            source = Path(*source.parts[source.parts.index("work"):])
        with Image.open(root / source) as image:
            image.convert("RGB").save(output / (case["id"] + ".png"))
        rows.append({key: case[key] for key in ("id", "gold", "condition")})
    (output / "manifest.json").write_text(json.dumps(rows))


def words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def score(path):
    counts = Counter()
    for row in json.loads(path.read_text()):
        gold, raw = words(row["gold"]), words(row["raw"])
        final = raw or words(row["enhanced"] or "")
        counts.update(total=1, before=int(raw == gold), after=int(final == gold),
                      missing_before=int(bool(gold) and not raw),
                      missing_after=int(bool(gold) and not final),
                      empty=int(not gold), false_positive=int(not gold and bool(final)))
    print(dict(counts))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("root", type=Path)
    prep.add_argument("output", type=Path)
    sub.add_parser("score").add_argument("result", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.root, args.output)
    else:
        score(args.result)
