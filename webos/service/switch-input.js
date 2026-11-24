/* SPDX-License-Identifier: AGPL-3.0-or-later */
"use strict";

exports.register = function (service) {
    var busy = false;
    service.register("switchInput", function (message) {
        var id = message.payload && message.payload.id;
        if (busy || typeof id !== "string" || !/^com\.webos\.app\.hdmi[1-9][0-9]*$/.test(id)) {
            message.respond({ returnValue: false, errorText: busy ? "Přepnutí už probíhá." : "Neplatný HDMI vstup." });
            return;
        }
        busy = true;
        var activity = null;
        var finished = false;
        var timer = setTimeout(function () { finish(false, "LG včas nepotvrdila přepnutí vstupu."); }, 12000);
        var relaunchTimer1 = null;
        var relaunchTimer2 = null;
        function complete(value) {
            if (value) {
                try { service.activityManager.complete(value, function () {}); }
                catch (error) { console.error("PlayCaption: ukončení přepínací aktivity selhalo."); }
            }
        }
        function finish(ok, error) {
            if (finished) return;
            finished = true;
            clearTimeout(timer);
            if (relaunchTimer1) { clearTimeout(relaunchTimer1); relaunchTimer1 = null; }
            if (relaunchTimer2) { clearTimeout(relaunchTimer2); relaunchTimer2 = null; }
            busy = false;
            complete(activity);
            message.respond({ returnValue: ok, errorText: error || "" });
        }
        function call(uri, payload, next) {
            try {
                service.call(uri, payload, function (reply) {
                    if (finished) return;
                    var result = reply.payload || {};
                    if (result.returnValue !== true) { finish(false, result.errorText || "LG odmítla přepnutí."); return; }
                    next(result);
                });
            } catch (error) { finish(false, "Volání LG selhalo."); }
        }
        try {
            // Při změně vstupu LG zavře UI; krátkou transakci dokončí služba na pozadí.
            service.activityManager.create("playcaption-input-switch", function (value) {
                if (finished) { complete(value); return; }
                activity = value;
                call("luna://com.webos.service.eim/getAllInputStatus", {}, function (result) {
                    if (!Array.isArray(result.devices) || !result.devices.some(function (device) { return device && device.appId === id; })) {
                        finish(false, "Vybraný HDMI vstup není na této TV dostupný.");
                        return;
                    }
                    call("luna://com.webos.applicationManager/launch", { id: id }, function () {
                        // Po přepnutí na HDMI (zvlášť PS5 s HDR/VRR/Game Optimizer) webOS
                        // shazuje overlay spuštěný moc brzy → počkat na ustálení HDMI a pro jistotu
                        // overlay po 3 s znovu oživit před uvolněním aktivity.
                        relaunchTimer1 = setTimeout(function () {
                            relaunchTimer1 = null;
                            if (finished) return;
                            call("luna://com.webos.applicationManager/launch", {
                                id: "cz.playcaption.overlaytest", params: { resumeOverlay: true }
                            }, function () {
                                relaunchTimer2 = setTimeout(function () {
                                    relaunchTimer2 = null;
                                    if (finished) return;
                                    call("luna://com.webos.applicationManager/launch", {
                                        id: "cz.playcaption.overlaytest", params: { resumeOverlay: true }
                                    }, function () { finish(true); });
                                }, 3000);
                            });
                        }, 2200);
                    });
                });
            });
        } catch (error) { finish(false, "Nepodařilo se spustit přepnutí na pozadí."); }
    });
};
