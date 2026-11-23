/* SPDX-License-Identifier: AGPL-3.0-or-later */
"use strict";
var Service = require("webos-service");
var dgram = require("dgram");
var os = require("os");
var crypto = require("crypto");
var parse = require("./protocol").parse;
var service = new Service(require("./package.json").name);
require("./switch-input").register(service);
var subscriptions = {};
var socket = null;
var announceTimer = null;
var ready = false;
var storyPeer = null;
var receiverName = "LG PlayCaption";
var networkReady = false;
var announcement = null;
var sender = null;
var senderSeen = 0;
var rejectedAt = 0;

// Toast režim: na TV, které neudrží web-overlay přes externí HDMI (např. webOS 6 Lite),
// se titulek ukáže jako systémový toast – ten webOS kreslí i přes obraz z HDMI.
// Službu přitom drží naživu trvalá aktivita, takže poslouchá dál i po zavření UI appky.
var toastMode = false;
var keepActivity = null;      // aktivita držící službu naživu (null = žádná, jinak hodnota od activityManageru)
var lastToastId = null;
var lastToastText = "";
var lastToastAt = 0;
var toastStory = null;

function showToast(text) {
    text = String(text == null ? "" : text).replace(/\s+/g, " ").trim().slice(0, 200);
    var now = Date.now();
    // stejný text do 4 s neopakuj (OCR posílá tutéž repliku z více snímků)
    if (!text || (text === lastToastText && now - lastToastAt < 4000)) return;
    lastToastText = text;
    lastToastAt = now;
    requestToast(text);
}

function closeToast() {
    if (lastToastId) {
        try { service.call("luna://com.webos.notification/closeToast", { toastId: lastToastId }, function () {}); } catch (error) {}
        lastToastId = null;
    }
}

function requestToast(text, accepted) {
    closeToast();
    try {
        service.call("luna://com.webos.notification/createToast", { message: text }, function (reply) {
            var result = reply && reply.payload;
            var ok = !!result && result.returnValue === true;
            if (accepted && !accepted(ok)) return;
            if (ok && result.toastId) lastToastId = result.toastId;
        });
    } catch (error) { if (accepted) accepted(false); }
}

// Systémový toast pojme omezený text. Posílej krátké stránky bez dělení znaků v páru.
function storyPages(text) {
    return text.match(/\S+/g)
        .reduce(function (pages, word) {
            var i = pages.length - 1;
            if (i >= 0 && pages[i].length + 1 + word.length <= 64) pages[i] += " " + word;
            else while (word.length) {
                var end = Math.min(word.length, 64);
                if (end < word.length && /[\uD800-\uDBFF]/.test(word.charAt(end - 1))) end--;
                pages.push(word.slice(0, end)); word = word.slice(end);
            }
            return pages;
        }, []);
}

function toastStoryState(packet, now) {
    var page = toastStory;
    if (!page || page.id !== packet.id) {
        if (page) {
            var old = page.id.split("."), next = packet.id.split(".");
            if (old[0] === next[0]) {
                for (var n = 1; n < 4; n++) {
                    if (+next[n] < +old[n]) return null;
                    if (+next[n] > +old[n]) break;
                }
            }
            closeToast();
        }
        page = toastStory = {id:packet.id, pages:packet.text.trim() ? storyPages(packet.text) : [],
            index:0, until:0, retryAt:0, pending:null, lastSeen:now};
    }
    if (page.index === page.pages.length) return "done";
    if (packet.paused || now - page.lastSeen > 5000) {
        if (page.until || page.pending) closeToast();
        page.until = 0; page.pending = null; page.retryAt = 0;
    }
    page.lastSeen = now;
    if (packet.paused) return "reading";
    if (page.until && now >= page.until) {
        page.index++; page.until = 0; page.retryAt = 0;
        if (page.index === page.pages.length) return "done";
    }
    if (!page.until && now >= page.retryAt) {
        var text = page.pages[page.index], attempt = {};
        page.pending = attempt;
        page.retryAt = now + 5000; // Luna může přijít o odpověď; opakování posílá telefon.
        requestToast(text, function (ok) {
            if (toastStory !== page || page.pending !== attempt) return false;
            page.pending = null;
            if (ok) {
                var letters = text.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, "_").length;
                var duration = packet.adjustableTiming ?
                    Math.ceil(Math.max(1500, Math.ceil(letters * 1000 / 18)) * packet.durationPercent * 80 / 10000) :
                    Math.max(1800, Math.ceil(letters * 1000 / 14));
                page.until = Date.now() + duration;
            } else page.retryAt = Date.now() + 1000;
            return ok;
        });
    }
    return page.until ? "reading" : null;
}

// Trvalá aktivita udrží službu (a UDP socket) naživu i bez otevřené UI appky.
function startKeepAlive() {
    if (keepActivity !== null) return;
    keepActivity = false; // „probíhá vytváření"
    try {
        service.activityManager.create("playcaption-toast-keepalive", function (value) {
            keepActivity = value || true;
        });
    } catch (error) { keepActivity = null; }
}
function stopKeepAlive() {
    if (keepActivity && keepActivity !== true) {
        try { service.activityManager.complete(keepActivity, function () {}); } catch (error) {}
    }
    keepActivity = null;
}

function notify(payload) {
    Object.keys(subscriptions).forEach(function (key) { subscriptions[key].respond(payload); });
}

function stop() {
    clearInterval(announceTimer);
    announceTimer = null;
    ready = false;
    networkReady = false;
    announcement = null;
    storyPeer = null;
    toastStory = null;
    sender = null;
    senderSeen = 0;
    rejectedAt = 0;
    if (socket) { try { socket.close(); } catch (error) {} }
    socket = null;
}

function start() {
    if (socket) return;
    var receiver = dgram.createSocket("udp4");
    socket = receiver;
    function announce() {
        if (socket !== receiver) return;
        // Po obnovení Wi-Fi/LAN se IP i broadcast mohou změnit bez restartu služby.
        var addresses = [];
        var identity = os.hostname();
        var interfaces = os.networkInterfaces();
        Object.keys(interfaces).forEach(function (name) {
            interfaces[name].forEach(function (iface) {
                if (iface.internal || iface.family !== "IPv4") return;
                identity += iface.mac;
                var ip = iface.address.split(".");
                var mask = iface.netmask.split(".");
                addresses.push(ip.map(function (part, i) { return Number(part) | (255 ^ Number(mask[i])); }).join("."));
            });
        });
        var id = crypto.createHash("sha256").update(identity).digest("hex").slice(0, 12);
        var name = "LG PlayCaption · " + id.toUpperCase();
        var changed = receiverName !== name || networkReady !== (addresses.length > 0);
        receiverName = name;
        networkReady = addresses.length > 0;
        announcement = Buffer.from("PLAYCAPTION_TV\tlg-" + id + "\t" + receiverName + "\t0", "utf8");
        if (changed) notify({ returnValue:true, ready:ready, networkReady:networkReady, name:receiverName });
        addresses.forEach(function (address) {
            receiver.send(announcement, 41988, address, function (error) {
                if (error) console.error("PlayCaption discovery:", error.code);
            });
        });
    }
    receiver.on("error", function (error) {
        if (socket !== receiver) return;
        notify({ returnValue: false, errorText: "Příjem UDP selhal: " + error.code });
        stop();
    });
    receiver.on("message", function (buffer, remote) {
        if (socket !== receiver) return;
        var query = buffer.length === 49 && /^PLAYCAPTION_FIND\t([a-f0-9]{32})$/.exec(buffer.toString("utf8"));
        if (query && ready && announcement) {
            receiver.send(Buffer.concat([announcement, Buffer.from("\t" + query[1])]), remote.port, remote.address, function () {});
            return;
        }
        var packet = parse(buffer);
        var now = Date.now();
        if (!packet) return;
        // Starý Android mění UDP porty: do budoucího párování držíme zdroj podle IP.
        if (sender && sender !== remote.address && now - senderSeen < 5000) {
            if (!rejectedAt || now - rejectedAt >= 5000) {
                rejectedAt = now;
                notify({ returnValue: true, otherSender: true });
            }
            return;
        }
        if (packet.kind === "storyQuery") {
            receiver.send(Buffer.from("PCA1\t" + packet.id + "\tready"), remote.port, remote.address, function () {});
            return;
        }
        if (sender !== remote.address) { storyPeer = null; toastStory = null; }
        sender = remote.address;
        senderSeen = now;
        if (packet.kind === "story") storyPeer = {id:packet.id,address:remote.address,port:remote.port};
        notify({ returnValue: true, packet: packet, source: remote.address });
        // Hybrid: když žije web-overlay (má odběratele), ukáže titulek on (hezký pruh).
        // Když overlay nežije (např. po přepnutí na HDMI na webOS Lite), nastoupí systémový toast.
        if (toastMode && Object.keys(subscriptions).length === 0) {
            if (packet.kind === "story") {
                var state = toastStoryState(packet, now);
                if (state) {
                    var ack = Buffer.from("PCA1\t" + packet.id + "\t" + state);
                    receiver.send(ack, 0, ack.length, remote.port, remote.address, function () {});
                }
            } else if (packet.kind === "subtitle" && packet.text) {
                if (toastStory && toastStory.index < toastStory.pages.length) {
                    toastStory.until = 0; toastStory.pending = null; toastStory.retryAt = 0;
                }
                showToast(packet.text);
            }
        }
    });
    receiver.bind(41989, "0.0.0.0", function () {
        if (socket !== receiver) return;
        receiver.setBroadcast(true);
        ready = true;
        announce();
        notify({ returnValue: true, ready: true, networkReady: networkReady, name: receiverName });
        announceTimer = setInterval(announce, 2000);
    });
}

service.register("listen", function (message) {
    if (!message.isSubscription) {
        message.respond({ returnValue: false, errorText: "Vyžadováno subscribe: true." });
        return;
    }
    if (toastStory) { toastStory = null; closeToast(); }
    subscriptions[message.uniqueToken] = message;
    message.respond({ returnValue: true, ready: ready, networkReady: networkReady, name: receiverName });
    start();
}, function (message) {
    delete subscriptions[message.uniqueToken];
    // V toast režimu nech socket otevřený i bez UI appky (poslouchá dál přes HDMI).
    if (Object.keys(subscriptions).length === 0 && !toastMode) stop();
});

// Zapne/vypne toast režim. UI appka ho zapne, když uživatel zvolí HDMI vstup,
// aby titulky chodily jako systémové toasty i poté, co ji přepnutí na HDMI zavře.
service.register("toastMode", function (message) {
    var on = !message.payload || message.payload.on !== false;
    toastMode = on;
    if (on) {
        start();
        startKeepAlive();
        if (message.payload && message.payload.hello) showToast(String(message.payload.hello));
    } else {
        toastStory = null;
        stopKeepAlive();
        if (Object.keys(subscriptions).length === 0) stop();
    }
    message.respond({ returnValue: true, toastMode: toastMode, ready: ready });
});

service.register("storyAck", function (message) {
    var p = message.payload || {};
    if (!socket || !storyPeer || p.id !== storyPeer.id || ["reading", "done"].indexOf(p.state) < 0) {
        message.respond({returnValue:false}); return;
    }
    socket.send(Buffer.from("PCA1\t" + p.id + "\t" + p.state), storyPeer.port, storyPeer.address, function (error) {
        message.respond({returnValue:!error});
    });
});
