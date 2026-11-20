// Titulek a žlutý úkol mise se v TV nepřekrývají (rozlozeni.js), bez prohlížeče.
"use strict";
const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

function prvek(rect) {
    const attrs = {};
    return {
        hidden: false, textContent: "", style: { bottom: "" }, rect: rect,
        getAttribute: (k) => (k in attrs ? attrs[k] : null), setAttribute: (k, v) => { attrs[k] = String(v); },
        getBoundingClientRect() { return this.rect; }
    };
}
const okno = { innerHeight: 1080, document: { documentElement: { clientHeight: 1080 } } };
okno.window = okno;
vm.runInNewContext(fs.readFileSync(__dirname + "/app/rozlozeni.js", "utf8"), okno);
const rozloz = okno.PlayCaptionRozlozeni;

// úkol dole (2 % odspodu, 70 px vysoký) → horní hrana 988 px
const titulek = prvek(null);
const ukol = prvek({ top: 988, bottom: 1058, height: 70 });
titulek.zakladBottom = "9%";
ukol.textContent = "Setřes fízly.";
rozloz(titulek, ukol);
const spodekTitulku = 1080 - parseFloat(titulek.style.bottom);
assert.ok(spodekTitulku < ukol.rect.top, "titulek musí končit nad úkolem: " + spodekTitulku + " vs " + ukol.rect.top);
assert.strictEqual(titulek.style.bottom, "104px");

// úkol zmizí → titulek zpět na polohu z tabletu
ukol.hidden = true;
rozloz(titulek, ukol);
assert.strictEqual(titulek.style.bottom, "9%");

// tablet posunul titulek výš (40 % odspodu) → úkol ho dolů nestáhne
titulek.zakladBottom = "40%";
ukol.hidden = false;
rozloz(titulek, ukol);
assert.strictEqual(titulek.style.bottom, "432px");
ukol.hidden = true;
rozloz(titulek, ukol);
assert.strictEqual(titulek.style.bottom, "40%");

// skrytý výstup (nulové rozměry) → nic neměnit
titulek.zakladBottom = "9%";
titulek.style.bottom = "9%";
ukol.hidden = false;
ukol.rect = { top: 0, bottom: 0, height: 0 };
rozloz(titulek, ukol);
assert.strictEqual(titulek.style.bottom, "9%");

// jen ES5 (Chromium 38 v TV)
const zdroj = fs.readFileSync(__dirname + "/app/rozlozeni.js", "utf8");
assert.ok(!/\b(let|const)\s|=>|`/.test(zdroj), "rozlozeni.js musí být ES5");
console.log("OK: titulek nad úkolem mise, návrat na polohu z tabletu, posun výš zachován, skrytý výstup, ES5.");
