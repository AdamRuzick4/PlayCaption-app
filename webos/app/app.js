/* SPDX-License-Identifier: AGPL-3.0-or-later */
(function () {
    "use strict";
    var status = document.getElementById("status");
    var subtitle = document.getElementById("subtitle");
    var hint = document.getElementById("hint");
    var vodici = document.getElementById("vodici"); // vodicí čára středu při úpravě polohy
    var receiverUI = window.PlayCaptionReceiverUI ? new window.PlayCaptionReceiverUI(document) : null;
    var inputPanel = document.getElementById("inputs");
    var inputBridge = null;
    var inputButtons = [];
    var inputIndex = 0;
    var bridge = null;
    var activityBridge = null;
    var activityWarning = "";
    var protocolWarning = "";
    var statusText = "";
    var lastActivity = 0;
    var timer = null;
    var lastSeen = 0;
    var lastId = null;
    var source = null;
    var subtitleUntil = 0;
    var hintUntil = 0;
    var controls = document.getElementById("controls");
    var output = document.getElementById("output");
    var testSubtitle = document.getElementById("test-subtitle");
    var controlButtons = [];
    var controlIndex = 0;
    var hiddenByUser = false;
    var testUntil = 0;
    var lastDataAt = 0;
    var lastCaptionAt = 0;
    var otherSender = false;
    var receiverReady = false;
    var receiverNetworkReady = true;
    var receiverStartedAt = 0;
    var receiverFailure = false;
    var receiverLabel = "LG PlayCaption";
    var receiverVersion = "neznámá";

    function updateInfo(now) {
        if (!controls || controls.hidden) return;
        var state = activityWarning || protocolWarning || (!receiverReady || !receiverNetworkReady || receiverFailure ? statusText : lastDataAt ?
            (now - lastDataAt <= 5000 ? "Zařízení posílá platná data." : "Komunikace byla přerušena.") : "Přijímač je připraven, čeká na zařízení.");
        var saved = "nezvolen";
        try { saved = localStorage.getItem("playcaption-input") || saved; } catch (error) {}
        var port = /hdmi([1-9][0-9]*)$/.exec(saved);
        document.getElementById("connection-info").textContent = receiverLabel + " · verze " + receiverVersion + "\n" + state +
            "\nPoslední data: " + (lastDataAt ? Math.max(0, Math.floor((now - lastDataAt) / 1000)) + " s" : "žádná") +
            " · poslední titulek: " + (lastCaptionAt ? Math.max(0, Math.floor((now - lastCaptionAt) / 1000)) + " s" : "žádný") +
            "\nNaposledy vybraný vstup: " + (port ? "HDMI " + port[1] : "nezvolen") +
            (otherSender ? "\nPříjem z dalšího zařízení byl odmítnut, původní zdroj je stále aktivní." : "") +
            (hiddenByUser ? "\nTitulky jsou dočasně skryté; Příběhový režim čeká na zobrazení." : "");
    }

    function storyMasked(now) {
        return hiddenByUser || now < testUntil || (controls && !controls.hidden) || (inputPanel && !inputPanel.hidden);
    }

    function pauseMaskedStory(now) {
        if (receiverUI && receiverUI.storyActive() && storyMasked(now)) receiverUI.pauseStory(now);
    }

    function showControls(visible) {
        if (!controls) return;
        controls.hidden = !visible;
        pauseMaskedStory(Date.now());
        if (visible) { controlIndex = 0; controlButtons[0].focus(); updateInfo(Date.now()); }
    }

    if (controls) {
        controlButtons = ["change-input", "local-test", "toggle-output", "return-game", "close-app"].map(function (id) { return document.getElementById(id); });
        controlButtons[0].onclick = function () { showControls(false); configureLaunch({}); };
        controlButtons[1].onclick = function () {
            showControls(false);
            testSubtitle.textContent = "Lokální test zobrazení · Příliš žluťoučký kůň.\nTento text neověřuje spojení ani překlad.";
            testSubtitle.hidden = false;
            testUntil = Date.now() + 5000;
            output.hidden = true;
            pauseMaskedStory(Date.now());
        };
        controlButtons[2].onclick = function () {
            hiddenByUser = !hiddenByUser;
            output.hidden = hiddenByUser || testUntil > Date.now();
            pauseMaskedStory(Date.now());
            this.textContent = hiddenByUser ? "Znovu zobrazit titulky" : "Dočasně skrýt titulky";
            updateInfo(Date.now());
        };
        controlButtons[3].onclick = function () { showControls(false); };
        controlButtons[4].onclick = function () { stop(); window.close(); };
        if (typeof XMLHttpRequest !== "undefined") {
            var metadata = new XMLHttpRequest();
            metadata.open("GET", "appinfo.json", true);
            metadata.onload = function () {
                try {
                    var info = JSON.parse(metadata.responseText);
                    if (/^\d+\.\d+\.\d+$/.test(info.version)) receiverVersion = info.version;
                } catch (error) {}
                updateInfo(Date.now());
            };
            metadata.send();
        }
    }

    function configureLaunch(params) {
        if (!inputPanel) return;
        var detail = params;
        if (!detail) {
            try { detail = JSON.parse(window.PalmSystem && window.PalmSystem.launchParams || "{}"); }
            catch (error) { detail = {}; }
        }
        inputPanel.hidden = detail && detail.resumeOverlay === true;
        if (inputPanel.hidden) return;
        var options = document.getElementById("input-options");
        var inputStatus = document.getElementById("input-status");
        var current = document.getElementById("input-current");
        options.textContent = "";
        inputButtons = [];
        inputStatus.textContent = "Načítám HDMI vstupy…";
        current.disabled = false;
        current.onclick = function () { inputPanel.hidden = true; };
        if (inputBridge) inputBridge.cancel();
        var request = new PalmServiceBridge();
        inputBridge = request;
        request.onservicecallback = function (raw) {
            if (inputBridge !== request) return;
            var result;
            try { result = JSON.parse(raw); } catch (error) { result = {}; }
            if (!result || typeof result !== "object") result = {};
            inputBridge = null;
            var devices = Array.isArray(result.devices) ? result.devices.filter(function (device) {
                return device && /^com\.webos\.app\.hdmi[1-9][0-9]*$/.test(device.appId || "");
            }) : [];
            if (!result.returnValue || !Array.isArray(result.devices)) {
                inputStatus.textContent = "LG neposkytla seznam vstupů. Pokud už vidíš obraz hry, zvol Pokračovat nad aktuálním obrazem. Jinak aplikaci zavři, vyber HDMI a znovu ji otevři.";
            } else {
                inputStatus.textContent = "";
                devices.forEach(function (device) {
                        var button = document.createElement("button");
                        button.textContent = "HDMI " + device.appId.match(/hdmi([1-9][0-9]*)$/)[1] +
                            (device.label ? " · " + String(device.label).slice(0, 100) : "");
                        button.onclick = function () {
                            inputStatus.textContent = "Přepínám vstup a spouštím titulky…";
                            inputButtons.forEach(function (item) { item.disabled = true; });
                            try { localStorage.setItem("playcaption-input", device.appId); } catch (error) {}
                            var switcher = new PalmServiceBridge();
                            inputBridge = switcher;
                            switcher.onservicecallback = function (answer) {
                                if (inputBridge !== switcher) return;
                                inputBridge = null;
                                var response;
                                try { response = JSON.parse(answer); } catch (error) { response = {}; }
                                if (!response || typeof response !== "object") response = {};
                                if (response.returnValue) inputPanel.hidden = true;
                                else {
                                    inputStatus.textContent = response.errorText || "Přepnutí se nepodařilo.";
                                    inputButtons.forEach(function (item) { item.disabled = false; });
                                }
                            };
                            switcher.call("luna://cz.playcaption.overlaytest.receiver/switchInput", JSON.stringify({ id: device.appId }));
                        };
                        options.appendChild(button);
                        inputButtons.push(button);
                    });
            }
            inputButtons.push(current);
            var saved;
            try { saved = localStorage.getItem("playcaption-input"); } catch (error) {}
            inputIndex = Math.max(0, devices.findIndex(function (device) { return device.appId === saved; }));
            inputButtons[inputIndex].focus();
        };
        request.call("luna://com.webos.service.eim/getAllInputStatus", "{}");
    }

    function showStatus(text) {
        statusText = text;
        status.textContent = activityWarning || protocolWarning || text;
        status.hidden = false;
    }

    function clearText(preserveStory) {
        if (receiverUI) {
            if (preserveStory && receiverUI.storyActive()) receiverUI.pauseStory(Date.now()); else receiverUI.clear();
        }
        subtitle.hidden = true;
        hint.hidden = true;
        hint.textContent = "";
        lastId = null;
        source = null;
        lastSeen = 0;
        otherSender = false;
    }

    function stop() {
        showControls(false);
        hiddenByUser = false; testUntil = 0; lastDataAt = 0; lastCaptionAt = 0;
        receiverReady = false; otherSender = false;
        if (output) output.hidden = false;
        if (testSubtitle) testSubtitle.hidden = true;
        if (controls) controlButtons[2].textContent = "Dočasně skrýt titulky";
        if (inputBridge) {
            inputBridge.onservicecallback = function () {};
            inputBridge.cancel();
            inputBridge = null;
        }
        if (timer !== null) clearInterval(timer);
        timer = null;
        if (activityBridge) {
            activityBridge.onservicecallback = function () {};
            activityBridge.cancel();
            activityBridge = null;
        }
        if (bridge) {
            bridge.onservicecallback = function () {};
            bridge.cancel();
            bridge = null;
        }
        clearText();
        activityWarning = "";
        protocolWarning = "";
    }

    function keepOverlayOpen() {
        lastActivity = Date.now();
        if (activityBridge) {
            activityBridge.onservicecallback = function () {};
            activityBridge.cancel();
        }
        var request = new PalmServiceBridge();
        activityBridge = request;
        request.onservicecallback = function (raw) {
            if (activityBridge !== request) return;
            var result;
            try { result = JSON.parse(raw); } catch (error) { result = {}; }
            activityWarning = result && result.returnValue === true ? "" :
                "PlayCaption · LG odmítá prodloužení překryvu, může se po 2 minutách zavřít.";
            status.textContent = activityWarning || protocolWarning || statusText;
            status.hidden = !activityWarning && !protocolWarning && Boolean(lastSeen);
            activityBridge = null;
        };
        // LG zavírá nečinný overlay po 120 s, i když se jeho text průběžně mění.
        request.call("luna://com.webos.surfacemanager.screenSaver/restartUserActivityTimer", "{}");
    }

    var ackBridge = null;
    function storyAck(id, state) {
        if (ackBridge) return;
        var request = new PalmServiceBridge(); ackBridge = request;
        var timeout = setTimeout(function () { if (ackBridge === request) { ackBridge = null; request.cancel(); } }, 2000);
        request.onservicecallback = function () { clearTimeout(timeout); if (ackBridge === request) ackBridge = null; };
        request.call("luna://cz.playcaption.overlaytest.receiver/storyAck", JSON.stringify({id:id,state:state}));
    }
    function receive(raw) {
        var result;
        try {
            result = JSON.parse(raw);
            if (!result || typeof result !== "object") throw new Error("Neplatná odpověď");
        } catch (error) {
            receiverReady = false; receiverFailure = true;
            protocolWarning = "";
            clearText();
            showStatus("PlayCaption: neplatná odpověď přijímače. Zkus aplikaci spustit znovu.");
            return;
        }
        if (!result.returnValue) {
            receiverReady = false; receiverFailure = true;
            protocolWarning = "";
            clearText();
            showStatus("PlayCaption: " + (result.errorText || "přijímač není dostupný"));
            return;
        }
        if (result.unsupportedStory) {
            protocolWarning = "PlayCaption LG · Tento přijímač nepodporuje Story. Na zařízení přepni na Standardní režim.";
            showStatus(statusText); updateInfo(Date.now()); return;
        }
        if (result.otherSender) { otherSender = true; updateInfo(Date.now()); return; }
        var packet = result.packet;
        if (!packet) {
            var name = typeof result.name === "string" ? result.name.slice(0, 64) : "LG PlayCaption";
            receiverLabel = name; receiverReady = Boolean(result.ready);
            receiverNetworkReady = result.networkReady !== false;
            if (receiverReady) receiverFailure = false;
            showStatus(result.ready && !receiverNetworkReady ? "PlayCaption LG · Čekám na síť TV. Zkontroluj Wi-Fi nebo síťový kabel · OK / Zpět = nabídka" : result.ready ? "PlayCaption LG · Čekám na zařízení. Vyber „" + name + "“ · OK / Zpět = nabídka" : receiverFailure ? statusText : "PlayCaption LG · Spouštím příjem…");
            return;
        }
        var now = Date.now();
        if (packet.kind === "subtitle" || packet.kind === "story") protocolWarning = "";
        receiverReady = true; receiverFailure = false;
        lastDataAt = now;
        statusText = "Zařízení posílá platná data. OK / Zpět = nabídka";
        var connectionChanged = source !== result.source || (lastSeen && now - lastSeen > 5000);
        if (connectionChanged) clearText(packet.kind === "story" && (!source || source === result.source));
        if (receiverUI && receiverUI.handle(packet, now)) {
            lastSeen = now;
            source = result.source;
            status.hidden = !activityWarning && !protocolWarning;
            return;
        }
        if (packet.kind !== "story" && receiverUI && receiverUI.storyActive()) return;
        if (packet.kind === "story" && receiverUI && receiverUI.oldStory(packet.id)) return;
        // Opakované pakety jsou heartbeat; nesmějí znovu zobrazit již prošlý titulek.
        var changed = packet.id !== lastId || connectionChanged;
        lastSeen = now;
        status.hidden = !activityWarning && !protocolWarning;
        if (!changed && packet.kind !== "story") return;
        lastId = packet.id;
        if (packet.text) lastCaptionAt = now;
        source = result.source;
        if (!receiverUI) subtitle.textContent = packet.text;
        subtitle.hidden = !packet.text;
        subtitleUntil = now + packet.duration;
        var layout = packet.layout;
        var width = layout ? layout[2] : 0.8;
        var left = layout ? Math.max(width / 2, Math.min(1 - width / 2, layout[0])) : 0.5;
        subtitle.style.left = (left * 100) + "%";
        subtitle.style.bottom = ((1 - (layout ? layout[1] : 0.91)) * 100) + "%";
        subtitle.style.width = (width * 100) + "%";
        var viewport = document.documentElement ? document.documentElement.clientHeight : 1080;
        subtitle.style.fontSize = Math.min(38 * (layout ? layout[3] : 1), (viewport * 0.18 - 32) / 2.7) + "px";
        if (receiverUI && packet.kind === "story") {
            subtitleUntil = Infinity;
            packet.paused = packet.paused || Boolean(storyMasked(now));
            var state = receiverUI.story(packet, now);
            if (state) storyAck(packet.id, state);
        } else if (receiverUI) receiverUI.caption(packet, now);
        if (packet.hint !== hint.textContent) {
            hint.textContent = packet.hint;
            hint.hidden = !packet.hint;
            hintUntil = now + 20000;
        }
    }

    function start() {
        stop();
        receiverStartedAt = Date.now(); receiverFailure = false;
        hint.textContent = "";
        showStatus("PlayCaption · Spouštím příjem…");
        if (typeof PalmServiceBridge === "undefined") {
            showStatus("PlayCaption: příjem je dostupný pouze na LG webOS.");
            return;
        }
        var request = new PalmServiceBridge();
        bridge = request;
        request.onservicecallback = function (raw) { if (bridge === request) receive(raw); };
        try { request.call("luna://cz.playcaption.overlaytest.receiver/listen", JSON.stringify({ subscribe: true })); }
        catch (error) { receiverFailure = true; showStatus("PlayCaption LG · Volání služby přijímače selhalo. Zavři aplikaci a spusť ji znovu."); }
        keepOverlayOpen();
        timer = setInterval(function () {
            var now = Date.now();
            if (!receiverReady && !receiverFailure && now - receiverStartedAt >= 10000) {
                receiverFailure = true;
                showStatus("PlayCaption LG · Služba nepotvrdila spuštění příjmu do 10 s. Zavři aplikaci a spusť ji znovu · OK / Zpět = nabídka");
            }
            if (receiverUI) {
                if (lastSeen && now - lastSeen > 5000) receiverUI.pauseStory(now);
                pauseMaskedStory(now);
                receiverUI.tick(now);
            }
            if (now - lastActivity >= 45000) keepOverlayOpen();
            if (now >= subtitleUntil) subtitle.hidden = true;
            if (now >= hintUntil) hint.hidden = true;
            if (vodici) vodici.hidden = !subtitle.style.outline;
            if (testSubtitle && now >= testUntil) testSubtitle.hidden = true;
            if (output) output.hidden = hiddenByUser || now < testUntil;
            if (lastSeen && now - lastSeen > 5000) {
                clearText(true);
                hint.textContent = "";
                showStatus("PlayCaption LG · Spojení přerušeno, čekám na zařízení · OK / Zpět = nabídka");
            }
            updateInfo(now);
        }, 250);
    }

    // shortcut: overlay není veřejný kontrakt LG, ověřit na každém cílovém firmwaru.
    document.addEventListener("webOSRelaunch", function (event) { start(); configureLaunch(event && event.detail); });
    document.addEventListener("keydown", function (event) {
        var back = event.keyCode === 461 || event.key === "Escape";
        if (controls && !controls.hidden) {
            if (back) { event.preventDefault(); showControls(false); return; }
            if (event.keyCode >= 37 && event.keyCode <= 40) {
                event.preventDefault();
                controlIndex = (controlIndex + (event.keyCode === 37 || event.keyCode === 38 ? -1 : 1) + controlButtons.length) % controlButtons.length;
                controlButtons[controlIndex].focus(); return;
            }
            if (event.keyCode === 13) { event.preventDefault(); controlButtons[controlIndex].onclick(); return; }
        }
        if (inputPanel && !inputPanel.hidden && inputButtons.length) {
            if (event.keyCode >= 37 && event.keyCode <= 40) {
                event.preventDefault();
                inputIndex = (inputIndex + (event.keyCode === 37 || event.keyCode === 38 ? -1 : 1) + inputButtons.length) % inputButtons.length;
                inputButtons[inputIndex].focus();
                return;
            }
        }
        if (controls && (!inputPanel || inputPanel.hidden) && (back || event.keyCode === 13)) {
            event.preventDefault(); showControls(true); return;
        }
        if (back) {
            event.preventDefault();
            stop();
            window.close();
        }
    });
    window.addEventListener("pagehide", stop);
    start();
    configureLaunch();
}());
