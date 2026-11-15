#!/usr/bin/env python3
"""Skúška a zostavenie Samsung prijímača bez knižníc navyše.

Výsledkom je NEPODPÍSANÝ balíček WGT. Samsung TV prijme iba balíček podpísaný
certifikátom, v ktorom je DUID konkrétnej televízie, preto sa podpis robí až
pri inštalácii (pozri NAVOD-SAMSUNG.md a INSTALACE-Z-ANDROIDU.md).
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
APP = HERE / "app"
ID = "PCaption01.PlayCaptionTest"


def main():
    config = (APP / "config.xml").read_text(encoding="utf8")
    version = re.search(r'<widget[^>]* version="([0-9]+\.[0-9]+\.[0-9]+)"', config).group(1)
    assert re.search(r'<tizen:application id="([^"]+)"', config).group(1) == ID, "Nesedí ID aplikácie"
    service = (APP / "service/service.js").read_text(encoding="utf8")
    assert re.search(r'var VERZIA = "([^"]+)";', service).group(1) == version, "Nesedí verzia služby a config.xml"
    subprocess.run([sys.executable, str(HERE / "sync.py"), "--check"], check=True)
    for source in ["app/js/main.js", "app/js/prijimac-ui.js", "app/service/protocol.js", "app/service/service.js"]:
        subprocess.run(["node", "--check", source], cwd=HERE, check=True)
    subprocess.run(["node", "test/test.cjs"], cwd=HERE, check=True)

    files = sorted(p for p in APP.rglob("*") if p.is_file())
    for path in files:
        if path.name.startswith(".") or path.name in ("author-signature.xml",) or path.name.startswith("signature"):
            raise ValueError("Do balíčka nepatrí: " + str(path))
    (HERE / "dist").mkdir(exist_ok=True)
    wgt = HERE / "dist" / ("PlayCaption-Samsung-" + version + "-nepodpisany.wgt")
    # Pevný čas a poradie: rovnaké zdroje dajú rovnaký súbor aj rovnaký SHA256.
    with zipfile.ZipFile(wgt, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(APP).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())
    with zipfile.ZipFile(wgt) as archive:
        assert archive.testzip() is None, "Poškodený WGT"
        assert set(archive.namelist()) == {p.relative_to(APP).as_posix() for p in files}
    data = wgt.read_bytes()
    manifest = {"schema": 1, "platform": "tizen", "id": ID, "version": version, "signed": False,
                "file": wgt.name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "protocols": ["PCT1", "PCV1", "PCO1", "PCQ1", "PCS1", "PCA1"], "story": True,
                "capabilities": ["subtitles", "hint", "layout", "speaker-colors", "choices", "text",
                                 "choice-frame", "ocr-frame", "sender-lease", "version-announce",
                                 "story-pages", "story-ack", "story-timing"]}
    (HERE / "dist/receiver-manifest.json").write_bytes(
        (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf8"))
    print("OK: nepodpísaný WGT " + version + " zodpovedá zdrojom.")
    print(wgt)


if __name__ == "__main__":
    main()
