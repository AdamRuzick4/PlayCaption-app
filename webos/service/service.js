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
var retryTimer = null;
var retryDelay = 1000;
var ready = false;
var storyPeer = null;
var receiverName = "LG PlayCaption";
var networkReady = false;
var announcement = null;
var sender = null;
var senderSeen = 0;
var rejectedAt = 0;

function notify(payload) {
    Object.keys(subscriptions).forEach(function (key) { subscriptions[key].respond(payload); });
}

function stop() {
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
    clearInterval(announceTimer);
    announceTimer = null;
    ready = false;
    networkReady = false;
    announcement = null;
    storyPeer = null;
    sender = null;
    senderSeen = 0;
    rejectedAt = 0;
    var previous = socket;
    socket = null;
    if (previous) { try { previous.close(); } catch (error) {} }
}

function retry(error) {
    stop();
    notify({ returnValue: false, errorText: "Příjem UDP selhal: " + (error.code || "UNKNOWN") });
    if (Object.keys(subscriptions).length === 0) return;
    retryTimer = setTimeout(function () { retryTimer = null; start(); }, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 15000);
}

function start() {
    if (socket || retryTimer !== null || Object.keys(subscriptions).length === 0) return;
    var receiver;
    try { receiver = dgram.createSocket("udp4"); }
    catch (error) { retry(error); return; }
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
        receiverName = name;
        networkReady = addresses.length > 0;
        announcement = Buffer.from("PLAYCAPTION_TV\tlg-" + id + "\t" + receiverName + "\t0", "utf8");
        // Stav je i heartbeat odběru Luna; ticho ve hře není výpadek služby.
        notify({ returnValue:true, ready:ready, networkReady:networkReady, name:receiverName });
        if (socket !== receiver) return;
        addresses.forEach(function (address) {
            receiver.send(announcement, 41988, address, function (error) {
                if (error) console.error("PlayCaption discovery:", error.code);
            });
        });
    }
    receiver.on("error", function (error) {
        if (socket !== receiver) return;
        retry(error);
    });
    receiver.on("close", function () { if (socket === receiver) retry({ code: "CLOSED" }); });
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
        if (sender !== remote.address) storyPeer = null;
        sender = remote.address;
        senderSeen = now;
        if (packet.kind === "story") storyPeer = {id:packet.id,address:remote.address,port:remote.port};
        notify({ returnValue: true, packet: packet, source: remote.address });
    });
    try { receiver.bind(41989, "0.0.0.0", function () {
        if (socket !== receiver) return;
        try {
            receiver.setBroadcast(true);
            ready = true;
            retryDelay = 1000;
            announce();
            if (socket === receiver) announceTimer = setInterval(announce, 2000);
        } catch (error) { if (socket === receiver) retry(error); }
    }); } catch (error) { if (socket === receiver) retry(error); }
}

service.register("listen", function (message) {
    if (!message.isSubscription) {
        message.respond({ returnValue: false, errorText: "Vyžadováno subscribe: true." });
        return;
    }
    subscriptions[message.uniqueToken] = message;
    message.respond({ returnValue: true, ready: ready, networkReady: networkReady, name: receiverName });
    start();
}, function (message) {
    delete subscriptions[message.uniqueToken];
    if (Object.keys(subscriptions).length === 0) { stop(); retryDelay = 1000; }
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
