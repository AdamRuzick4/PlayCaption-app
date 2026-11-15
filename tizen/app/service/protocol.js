/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Prevzaté z PlayCaption (webos/service/protocol.js, © Adam Růžička) skriptom tizen/sync.py. Zmena: Buffer.from nahradený funkciou pre starší Node v Samsung TV. Neupravovať ručne. */
"use strict";

function bytes(text) {
    if (Buffer.from && Buffer.from !== Uint8Array.from) return Buffer.from(text, "utf8");
    return new Buffer(text, "utf8");
}

function unescape(text) {
    return text.replace(/\\(.)/g, function (_, ch) { return ch === "n" ? "\n" : ch; });
}

function area(fields) {
    var values = fields.map(function (value) { return value.trim() === "" ? NaN : Number(value); });
    if (values.length !== 4 || !values.every(Number.isFinite)) return null;
    values = values.map(function (value) { return Math.max(0, Math.min(1, value)); });
    return values[2] > values[0] && values[3] > values[1] ? values : null;
}

exports.parse = function (buffer) {
    if (buffer.length > 8192) return null;
    var fields = buffer.toString("utf8").split("\t");
    if (fields[0] === "PCQ1" || fields[0] === "PCS1") {
        if (!/^[a-f0-9]{32}\.\d{1,10}\.\d{1,10}\.\d{1,10}$/.test(fields[1] || "")) return null;
        if (fields[0] === "PCQ1") return fields.length === 2 ? {kind:"storyQuery",id:fields[1]} : null;
        if ((fields.length !== 9 && fields.length !== 10) || !/^[01]$/.test(fields[2])) return null;
        var durationPercent = 100;
        if (fields.length === 10) {
            if (!/^D:\d{2,3}$/.test(fields[9])) return null;
            durationPercent = Number(fields[9].slice(2));
            if (durationPercent < 50 || durationPercent > 200) return null;
        }
        var converted = ["PCT1", "1", "60000", fields[3], ""].concat(fields.slice(4, 8), ["0", "B:" + fields[8]]);
        var parsed = exports.parse(bytes(converted.join("\t")));
        if (!parsed || !parsed.layout) return null;
        parsed.kind = "story"; parsed.id = fields[1]; parsed.paused = fields[2] === "1";
        parsed.durationPercent = durationPercent;
        parsed.adjustableTiming = fields.length === 10;
        return parsed;
    }
    if (fields[0] === "PCO1") {
        if (fields.length < 6 || !/^[01]$/.test(fields[1])) return null;
        var ocrArea = area(fields.slice(2, 6));
        return ocrArea ? { kind: "ocr", visible: fields[1] === "1", area: ocrArea } : null;
    }
    if (fields[0] === "PCV1") {
        if (fields.length < 9 || !/^\d{1,19}$/.test(fields[1]) || !/^[012]$/.test(fields[2]) ||
            !/^(?:-2|-1|\d{1,2})$/.test(fields[7]) || fields[8].length > 4096) return null;
        var choiceArea = area(fields.slice(3, 7));
        if (!choiceArea) return null;
        var text = unescape(fields[8]);
        var items = text.trim() ? text.split("\n") : [];
        if (items.length > 64) return null;
        var selected = Number(fields[7]);
        return { kind: "choices", id: fields[1], frame: Number(fields[2]), area: choiceArea,
            selected: selected < items.length ? selected : -1, items: items };
    }
    if (fields[0] !== "PCT1" || fields.length < 5 || !/^\d{1,19}$/.test(fields[1])) return null;
    if (fields[3].length > 4096 || fields[4].length > 2048) return null;
    var duration = /^\d+$/.test(fields[2]) ? Number(fields[2]) : 6000;
    var layout = null;
    if (fields.length >= 9 && fields[5].indexOf("B:") !== 0) {
        var values = fields.slice(5, 9).map(function (value) { return value.trim() === "" ? NaN : Number(value); });
        if (values.every(Number.isFinite)) {
            layout = [
                Math.max(0, Math.min(1, values[0])),
                Math.max(0.05, Math.min(1, values[1])),
                Math.max(0.2, Math.min(1, values[2])),
                Math.max(0.5, Math.min(2.5, values[3]))
            ];
        }
    }
    return {
        kind: "subtitle",
        id: fields[1],
        text: unescape(fields[3]),
        hint: unescape(fields[4]),
        duration: Math.min(60000, duration > 0 ? duration : 6000),
        layout: layout,
        editing: Boolean(layout && fields[9] === "1"),
        colors: (fields.find(function (value) { return value.indexOf("B:") === 0; }) || "B:").slice(2)
            .split(",").slice(0, 64).map(function (value) {
                var number = /^-?\d{1,10}$/.test(value) ? Number(value) : 0;
                return number >= -2147483648 && number <= 4294967295 ? number : 0;
            })
    };
};
