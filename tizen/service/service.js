/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Samsung prijímač PlayCaption: počúva titulky z telefónu cez UDP a posiela ich apke na obrazovke.
   Protokol je rovnaký ako v LG prijímači (webos/service/service.js v PlayCaption, © Adam Růžička). */
"use strict";

var PORT_HLADANIE = 41988;
var PORT_TITULKY = 41989;
var NAZOV_PORTU = "PLAYCAPTION_UI";

var socket = null;
var casovac = null;
var nazov = "Samsung PlayCaption";
var odosielatel = null;
var odosielatelVideny = 0;
var odmietnuteO = 0;
var bezApkyOd = 0;
var chybaTextu = "";
var pripravene = false;
var mojeAdresy = [];
var pocty = { ohlasenia: 0, titulky: 0, volby: 0, oblasti: 0, neplatne: 0, prevzatia: 0, odmietnute: 0 };

function idApky() {
    var ja = tizen.application.getCurrentApplication();
    try {
        var data = ja.getRequestedAppControl().appControl.data || [];
        for (var i = 0; i < data.length; i++) {
            if (data[i].key === "apka" && data[i].value && data[i].value[0]) return data[i].value[0];
        }
    } catch (e) {}
    return ja.appInfo.packageId + ".PlayCaptionTest";
}

function posli(sprava) {
    try {
        var port = tizen.messageport.requestRemoteMessagePort(globalneIdApky, NAZOV_PORTU);
        port.sendMessage([{ key: "d", value: JSON.stringify(sprava) }]);
        bezApkyOd = 0;
        return true;
    } catch (e) {
        if (!bezApkyOd) bezApkyOd = Date.now();
        return false;
    }
}

var globalneIdApky = "";

function zaznam(text) {
    posli({ t: "log", text: text });
}

function stavSluzby() {
    return { t: "pripravene", nazov: nazov, node: process.version, adresy: mojeAdresy, pocty: pocty, odosielatel: odosielatel };
}

function retazecNaBajty(text) {
    if (Buffer.from && Buffer.from !== Uint8Array.from) return Buffer.from(text, "utf8");
    return new Buffer(text, "utf8");
}

function zastav() {
    if (casovac) clearInterval(casovac);
    casovac = null;
    pripravene = false;
    if (socket) {
        try { socket.close(); } catch (e) {}
    }
    socket = null;
}

function koniec() {
    zastav();
    try { tizen.application.getCurrentApplication().exit(); } catch (e) {}
}

function spusti() {
    if (socket) return;
    var dgram, os, crypto, parse;
    try {
        dgram = require("dgram");
        os = require("os");
        crypto = require("crypto");
        parse = require("./protocol").parse;
    } catch (e) {
        chybaTextu = "Služba nemá sieťové moduly: " + (e && e.message ? e.message : e);
        posli({ t: "chyba", text: chybaTextu });
        return;
    }

    var prijimac = dgram.createSocket("udp4");
    socket = prijimac;
    var adresy = [];
    mojeAdresy = [];
    var identita = os.hostname();
    var rozhrania = {};
    try { rozhrania = os.networkInterfaces() || {}; } catch (e) {}
    Object.keys(rozhrania).forEach(function (meno) {
        (rozhrania[meno] || []).forEach(function (r) {
            if (r.internal || r.family !== "IPv4") return;
            identita += r.mac || r.address;
            mojeAdresy.push(meno + " " + r.address);
            if (!r.netmask) return;
            var ip = r.address.split(".");
            var maska = r.netmask.split(".");
            adresy.push(ip.map(function (cast, i) { return Number(cast) | (255 ^ Number(maska[i])); }).join("."));
        });
    });
    if (adresy.length === 0) adresy.push("255.255.255.255");
    var id = crypto.createHash("sha256").update(identita).digest("hex").slice(0, 12);
    nazov = "Samsung PlayCaption · " + id.toUpperCase();
    var ohlasenie = retazecNaBajty("PLAYCAPTION_TV\tsamsung-" + id + "\t" + nazov + "\t0");

    function tik() {
        adresy.forEach(function (adresa) {
            prijimac.send(ohlasenie, 0, ohlasenie.length, PORT_HLADANIE, adresa, function (e) {
                if (e) zaznam("Ohlásenie na " + adresa + " zlyhalo: " + (e.code || e.message));
            });
        });
        pocty.ohlasenia++;
        posli(stavSluzby());
        if (bezApkyOd && Date.now() - bezApkyOd > 10000) koniec();
    }

    prijimac.on("error", function (e) {
        chybaTextu = "Príjem UDP zlyhal: " + (e && (e.code || e.message) ? (e.code || e.message) : e);
        posli({ t: "chyba", text: chybaTextu });
        zastav();
    });

    prijimac.on("message", function (bajty, odkial) {
        var paket = parse(bajty);
        var teraz = Date.now();
        if (!paket) {
            // Telefón chce prevziať prihlásenie ku konzole z televízie. Samsung žiadne nemá,
            // odpovie preto rovnako ako nespárovaná Android TV a telefón sa prihlási sám.
            if (bajty.length <= 512 && bajty.toString("utf8").indexOf("PCS1\t") === 0) {
                pocty.prevzatia++;
                zaznam("Žiadosť o prevzatie prihlásenia z " + odkial.address + ", odpovedám: nespárované");
                var odpoved = retazecNaBajty("PCS1\tCHYBA\tnesparovano");
                prijimac.send(odpoved, 0, odpoved.length, odkial.port, odkial.address, function () {});
            } else {
                pocty.neplatne++;
                zaznam("Neznáma správa z " + odkial.address + " (" + bajty.length + " B): " +
                    bajty.toString("utf8").slice(0, 60).replace(/[\t\n\r]/g, " "));
            }
            return;
        }
        if (odosielatel && odosielatel !== odkial.address && teraz - odosielatelVideny < 5000) {
            pocty.odmietnute++;
            if (!odmietnuteO || teraz - odmietnuteO >= 5000) {
                odmietnuteO = teraz;
                posli({ t: "iny" });
            }
            return;
        }
        if (odosielatel !== odkial.address) zaznam("Titulky posiela " + odkial.address);
        odosielatel = odkial.address;
        odosielatelVideny = teraz;
        if (paket.kind === "subtitle") pocty.titulky++;
        else if (paket.kind === "choices") pocty.volby++;
        else pocty.oblasti++;
        posli({ t: "paket", paket: paket, zdroj: odkial.address });
    });

    prijimac.bind(PORT_TITULKY, "0.0.0.0", function () {
        try { prijimac.setBroadcast(true); } catch (e) {}
        pripravene = true;
        chybaTextu = "";
        zaznam("UDP počúva na porte " + PORT_TITULKY + ", ohlasujem sa na " + adresy.join(", "));
        tik();
        casovac = setInterval(tik, 2000);
    });
}

function start() {
    try {
        globalneIdApky = idApky();
    } catch (e) {
        return;
    }
    bezApkyOd = 0;
    if (pripravene) {
        posli(stavSluzby());
    } else if (!socket) {
        spusti();
    }
}

module.exports.onStart = function () {
    start();
};

module.exports.onRequest = function () {
    start();
};

module.exports.onExit = function () {
    zastav();
};
