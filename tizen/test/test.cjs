/* Skúška bez televízie: služba dostane UDP pakety ako z telefónu, stránka ich vykreslí v Chromiu.
   Spustenie: node test/test.cjs */
"use strict";
const dgram = require("dgram");
const path = require("path");
const assert = require("assert");
// Playwright je voliteľný: bez neho sa overí služba a sieť, vykreslenie v prehliadači sa preskočí.
let chromium = null;
try { chromium = require("playwright").chromium; } catch (e) {}

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
    }, requestLocalMessagePort: (port) => {
        assert.strictEqual(port, "PLAYCAPTION_SLUZBA");
        return { addMessagePortListener: (f) => { global.doSluzby = f; } };
    } }
};
const sluzba = require("../app/service/service.js");
const verzia = /<widget[^>]* version="([0-9.]+)"/.exec(require("fs").readFileSync(path.resolve(__dirname, "../app/config.xml"), "utf8"))[1];
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
    // Príbehový režim: dotaz na podporu, replika a potvrdenie stavu späť telefónu.
    const koren = "a".repeat(32);
    const dalsia = () => new Promise((r) => telefon.once("message", (b) => r(b.toString("utf8"))));
    let caka = dalsia();
    await posli("PCQ1\t" + koren + ".1.0.0");
    assert.strictEqual(await caka, "PCA1\t" + koren + ".1.0.0\tready");
    const replika = "PCS1\t" + koren + ".1.1.0\t0\tMarek: Ideme.\t0.5\t0.9\t0.7\t1.0\t-65536\tD:100";
    await posli(replika);
    await cakaj(100);
    caka = dalsia();
    global.doSluzby([{ key: "d", value: JSON.stringify({ t: "pribehStav", id: koren + ".1.1.0", stav: "reading" }) }]);
    assert.strictEqual(await caka, "PCA1\t" + koren + ".1.1.0\treading");
    global.doSluzby([{ key: "d", value: JSON.stringify({ t: "pribehStav", id: koren + ".9.9.9", stav: "done" }) }]);
    await cakaj(2300);

    const typy = spravy.map((d) => JSON.parse(d[0].value));
    assert.ok(typy.some((s) => s.t === "pripravene" && /^Samsung PlayCaption · [0-9A-F]{12}$/.test(s.nazov)), "služba sa neohlásila apke");
    assert.ok(typy.some((s) => s.t === "pripravene" && s.verzia === verzia && s.pribeh === true), "služba neohlásila verziu a Príbeh");
    assert.deepStrictEqual(typy.filter((s) => s.t === "paket").map((s) => s.paket.kind), ["subtitle", "choices", "ocr", "story"]);
    if (ohlasenia[0]) assert.ok(new RegExp("^PLAYCAPTION_TV\\tsamsung-[0-9a-f]{12}\\tSamsung PlayCaption · [0-9A-F]{12}\\t0\\t" + verzia.replace(/\./g, "\\.") + "$").test(ohlasenia[0]), ohlasenia[0]);
    console.log("ohlásenia na 41988:", ohlasenia.length, ohlasenia[0] || "(broadcast v tomto prostredí neprišiel)");

    if (!chromium) {
        sluzba.onExit();
        telefon.close();
        hladac.close();
        console.log("SLUŽBA V PORIADKU (vykreslenie preskočené, chýba playwright)");
        return;
    }
    const prehliadac = await chromium.launch();
    const strana = await prehliadac.newPage({ viewport: { width: 1920, height: 1080 } });
    await strana.addInitScript(() => {
        window.preSluzbu = [];
        window.tizen = {
            application: {
                getCurrentApplication: () => ({ appInfo: { packageId: "PCaption01", id: "PCaption01.PlayCaptionTest" }, exit() {} }),
                launchAppControl: (c, id) => { window.spustenaSluzba = id; }
            },
            ApplicationControl: function () {},
            ApplicationControlData: function () {},
            messageport: {
                requestLocalMessagePort: () => ({ addMessagePortListener: (f) => { window.odSluzby = f; } }),
                requestRemoteMessagePort: (id, port) => ({ sendMessage: (d) => { window.preSluzbu.push([id, port, JSON.parse(d[0].value)]); } })
            },
            systeminfo: { getPropertyValue: (n, ok) => ok({ connected: [{ type: "HDMI", number: 3 }], supported: [] }) },
            tvwindow: { setSource: (z, ok) => ok(), show: (ok) => ok(), hide() {} }
        };
    });
    await strana.goto("file://" + path.resolve(__dirname, "../app/index.html"));
    await strana.evaluate(() => { document.body.style.background = "#35506b"; });
    assert.strictEqual(await strana.evaluate(() => window.spustenaSluzba), "PCaption01.Prijimac");
    const jePribeh = (d) => { const s = JSON.parse(d[0].value); return s.t === "paket" && s.paket.kind === "story"; };
    for (const d of spravy.filter((d) => !jePribeh(d))) await strana.evaluate((x) => window.odSluzby(x), d);
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
    assert.ok(/Prijaté: titulky 1, voľby 1, oblasti 1, príbeh 1, neznáme 1, prevzatia 1/.test(stav.ladenie), "počty v ladení nesedia");
    assert.ok(/Neznáma správa z 127\.0\.0\.1 \(8 B\): nezmysel/.test(stav.ladenie));
    assert.ok(/Žiadosť o prevzatie prihlásenia/.test(stav.ladenie));
    assert.ok(/Titulok #7 \(6000 ms\): Elena/.test(stav.ladenie));
    assert.ok(/HDMI 3 beží/.test(stav.stav) && /Telefón posiela titulky/.test(stav.stav));
    assert.ok(new RegExp("verzia " + verzia.replace(/\./g, "\\.")).test(stav.ladenie), "ladenie neukazuje verziu");
    await strana.screenshot({ path: process.argv[2] || path.resolve(__dirname, "nahlad.png") });

    // Príbeh: replika sa ukáže, apka hlási "reading" a po dočítaní "done"; bežný titulok ju medzitým neprepíše.
    const pribeh = spravy.filter(jePribeh)[0];
    await strana.evaluate((x) => window.odSluzby(x), pribeh);
    await strana.evaluate((x) => window.odSluzby(x), spravy.filter((d) => JSON.parse(d[0].value).t === "paket")[0]);
    assert.strictEqual(await strana.evaluate(() => document.getElementById("subtitle").textContent), "Marek: Ideme.");
    await cakaj(1400);
    await strana.evaluate((x) => window.odSluzby(x), pribeh);
    const hlasenia = await strana.evaluate(() => window.preSluzbu);
    assert.deepStrictEqual(hlasenia[0], ["PCaption01.Prijimac", "PLAYCAPTION_SLUZBA", { t: "pribehStav", id: koren + ".1.1.0", stav: "reading" }]);
    assert.strictEqual(hlasenia[hlasenia.length - 1][2].stav, "done", "dočítaná replika sa má potvrdiť");
    assert.strictEqual(await strana.evaluate(() => document.getElementById("subtitle").hidden), true);

    await cakaj(5600);
    assert.strictEqual(await strana.evaluate(() => document.getElementById("subtitle").hidden), true, "po výpadku má titulok zmiznúť");
    assert.ok(/prerušené/.test(await strana.evaluate(() => document.getElementById("stav").textContent)));

    await prehliadac.close();
    sluzba.onExit();
    telefon.close();
    hladac.close();
    console.log("VŠETKO V PORIADKU");
})().catch((e) => { console.error(e); process.exit(1); });
