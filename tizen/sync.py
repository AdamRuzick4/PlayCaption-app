#!/usr/bin/env python3
"""Spoločný kód prijímača sa preberá z LG (webos/) s malými úpravami pre staršie Samsung TV.

Spustenie bez parametra súbory v tizen/app prepíše, s parametrom --check iba overí,
že sa od LG zdrojov nerozišli (používa to build.py).
"""
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
WEBOS = HERE.parent / "webos"
SPDX = "/* SPDX-License-Identifier: AGPL-3.0-or-later */\n"

# cieľ: (zdroj v LG, poznámka pod licenčným riadkom, [(pôvodný text, náhrada)])
SUBORY = {
    "app/service/protocol.js": (
        "service/protocol.js",
        "/* Prevzaté z PlayCaption (webos/service/protocol.js, © Adam Růžička) skriptom tizen/sync.py."
        " Zmena: Buffer.from nahradený funkciou pre starší Node v Samsung TV. Neupravovať ručne. */\n",
        [('"use strict";\n', '"use strict";\n\nfunction bytes(text) {\n'
          '    if (Buffer.from && Buffer.from !== Uint8Array.from) return Buffer.from(text, "utf8");\n'
          '    return new Buffer(text, "utf8");\n}\n'),
         ('Buffer.from(converted.join("\\t"), "utf8")', 'bytes(converted.join("\\t"))')],
    ),
    "app/js/prijimac-ui.js": (
        "app/receiver-ui.js",
        "/* Prevzaté z PlayCaption (webos/app/receiver-ui.js, © Adam Růžička) skriptom tizen/sync.py."
        " Zmena: trimEnd nahradený regulárnym výrazom kvôli starším Samsung TV. Neupravovať ručne. */\n",
        [(".trimEnd()", '.replace(/\\s+$/, "")')],
    ),
}


def vyrob(zdroj, poznamka, nahrady):
    text = (WEBOS / zdroj).read_text(encoding="utf8").replace("\r\n", "\n")
    if not text.startswith(SPDX):
        raise ValueError("Chýba licenčný riadok: " + zdroj)
    text = SPDX + poznamka + text[len(SPDX):]
    for stare, nove in nahrady:
        if text.count(stare) != 1:
            raise ValueError("Úprava pre Samsung už nesedí na LG zdroj " + zdroj + ": " + stare)
        text = text.replace(stare, nove)
    return text


def main():
    kontrola = "--check" in sys.argv
    for ciel, (zdroj, poznamka, nahrady) in SUBORY.items():
        text = vyrob(zdroj, poznamka, nahrady)
        cesta = HERE / ciel
        if kontrola:
            if cesta.read_text(encoding="utf8").replace("\r\n", "\n") != text:
                raise SystemExit(ciel + " sa líši od webos/" + zdroj + ". Spusti: python3 tizen/sync.py")
        else:
            cesta.write_bytes(text.encode("utf8"))
    print("OK: spoločný kód prijímača zodpovedá LG zdrojom.")


if __name__ == "__main__":
    main()
