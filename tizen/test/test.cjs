/* Skúška bez televízie: služba dostane UDP pakety ako z telefónu, stránka ich vykreslí v Chromiu.
   Spustenie: node test/test.cjs */
"use strict";
const dgram = require("dgram");
const path = require("path");
const assert = require("assert");
const { chromium } = require("playwright");

const spravy = [];
global.tizen = {
    application: { getCurrentApplication: () => ({
        appInfo: { packageId: "PCaption01" },
        getRequestedAppControl: () => ({ appControl: { data: [{ key: "apka", value: ["PCaption01.PlayCaptionTest"] }] } }),
        exit: () => {}
    }) },
    messageport: { requestRemoteMessagePort: (id, port) => {
        assert.strictEqual(id, "PCaption01.PlayCaptionTest");
        assert.strictEqual(port, "PLAYCAPTION_UI");
        return { sendMessage: (data) => spravy.push(data) };
    } }
};
const sluzba = require("../service/service.js");
const cakaj = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const hladac = dgram.createSocket({ type: "udp4", reuseAddr: true });
    const ohlasenia = [];
    hladac.on("message", (b) => ohlasenia.push(b.toString("utf8")));
    await new Promise((r) => hladac.bind(41988, r));

    sluzba.onStart();
    await cakaj(500);
    const telefon = dgram.createSocket("udp4");
    const posli = (t) => new Promise((r) => telefon.send(Buffer.from(t, "utf8"), 41989, "127.0.0.1", r));
    await posli("PCT1\t7\t6000\tElena: Musíme odtiaľto zmiznúť.\\nRýchlo!\t\t0.5\t0.9\t0.7\t1.0\t0\tB:-16711936,0");
    await posli("PCV1\t3\t1\t0.55\t0.3\t0.95\t0.6\t1\tOstať tu\\nUtiecť\\nBojovať");
    await posli("PCO1\t1\t0.2\t0.75\t0.8\t0.95");
    await posli("nezmysel");
    const odpovedPrevzatia = new Promise((r) => telefon.once("message", (b) => r(b.toString("utf8"))));
    await posli("PCS1\tZADOST\tTablet");
    assert.strictEqual(await odpovedPrevzatia, "PCS1\tCHYBA\tnesparovano");
    await cakaj(2300);

    const typy = spravy.map((d) => JSON.parse(d[0].value));
    assert.ok(typy.some((s) => s.t === "pripravene" && /^Samsung PlayCaption · [0-9A-F]{12}$/.test(s.nazov)), "služba sa neohlásila apke");
    assert.deepStrictEqual(typy.filter((s) => s.t === "paket").map((s) => s.paket.kind), ["subtitle", "choices", "ocr"]);
    console.log("ohlásenia na 41988:", ohlasenia.length, ohlasenia[0] || "(broadcast v tomto prostredí neprišiel)");

    const prehliadac = await chromium.launch();
    const strana = await prehliadac.newPage({ viewport: { width: 1920, height: 1080 } });
    await strana.addInitScript(() => {
        window.tizen = {
            application: {
                getCurrentApplication: () => ({ appInfo: { packageId: "PCaption01", id: "PCaption01.PlayCaptionTest" }, exit() {} }),
                launchAppControl: (c, id) => { window.spustenaSluzba = id; }
            },
            ApplicationControl: function () {},
            ApplicationControlData: function () {},
            messageport: { requestLocalMessagePort: () => ({ addMessagePortListener: (f) => { window.odSluzby = f; } }) },
            systeminfo: { getPropertyValue: (n, ok) => ok({ connected: [{ type: "HDMI", number: 3 }], supported: [] }) },
            tvwindow: { setSource: (z, ok) => ok(), show: (ok) => ok(), hide() {} }
        };
    });
    await strana.goto("file://" + path.resolve(__dirname, "../index.html"));
    await strana.evaluate(() => { document.body.style.background = "#35506b"; });
    assert.strictEqual(await strana.evaluate(() => window.spustenaSluzba), "PCaption01.Prijimac");
    for (const d of spravy) await strana.evaluate((x) => window.odSluzby(x), d);
    await cakaj(400);
    const stav = await strana.evaluate(() => ({
        titulok: document.getElementById("subtitle").textContent,
        skryty: document.getElementById("subtitle").hidden,
        meno: document.querySelector("#subtitle span") && document.querySelector("#subtitle span").style.color,
        volby: Array.from(document.querySelectorAll(".choice")).map((e) => e.textContent + (e.classList.contains("selected") ? "*" : "")),
        modry: !document.getElementById("ocr-frame").hidden,
        zlty: !document.getElementById("choice-frame").hidden,
        stav: document.getElementById("stav").textContent,
        ladenie: document.getElementById("debug").textContent
    }));
    console.log(stav);
    assert.strictEqual(stav.titulok, "Elena: Musíme odtiaľto zmiznúť.\nRýchlo!");
    assert.strictEqual(stav.skryty, false);
    assert.ok(/0, 255, 0/.test(stav.meno));
    assert.deepStrictEqual(stav.volby, ["Ostať tu", "Utiecť*", "Bojovať"]);
    assert.ok(stav.modry && stav.zlty);
    assert.ok(/Prijaté: titulky 1, voľby 1, oblasti 1, neznáme 1, prevzatia 1/.test(stav.ladenie), "počty v ladení nesedia");
    assert.ok(/Neznáma správa z 127\.0\.0\.1 \(8 B\): nezmysel/.test(stav.ladenie));
    assert.ok(/Žiadosť o prevzatie prihlásenia/.test(stav.ladenie));
    assert.ok(/Titulok #7 \(6000 ms\): Elena/.test(stav.ladenie));
    assert.ok(/HDMI 3 beží/.test(stav.stav) && /Telefón posiela titulky/.test(stav.stav));
    await strana.screenshot({ path: process.argv[2] || path.resolve(__dirname, "nahlad.png") });

    await cakaj(5600);
    assert.strictEqual(await strana.evaluate(() => document.getElementById("subtitle").hidden), true, "po výpadku má titulok zmiznúť");
    assert.ok(/prerušené/.test(await strana.evaluate(() => document.getElementById("stav").textContent)));

    await prehliadac.close();
    sluzba.onExit();
    telefon.close();
    hladac.close();
    console.log("VŠETKO V PORIADKU");
})().catch((e) => { console.error(e); process.exit(1); });
