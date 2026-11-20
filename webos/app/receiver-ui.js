/* SPDX-License-Identifier: AGPL-3.0-or-later */
(function () {
    "use strict";
    window.PlayCaptionReceiverUI = function (document) {
        var choices = document.getElementById("choices");
        var yellow = document.getElementById("choice-frame");
        var blue = document.getElementById("ocr-frame");
        var subtitle = document.getElementById("subtitle");
        var lastChoiceId = null;
        var lastFrameMode = 0;
        var choicesUntil = 0, yellowUntil = 0, blueUntil = 0, greenUntil = 0;
        var captionPacket = null, captionOffset = 0, captionNext = 0, captionUntil = 0, captionStart = 0;
        var storyId = null, storyMode = false, storyDone = false, storyPaused = false, storyRemaining = 0;

        function pageEnd(text) {
            var ends = [], word, words = /\S+/g;
            while ((word = words.exec(text))) ends.push(word.index + word[0].length);
            function fits(end) {
                subtitle.textContent = text.slice(0, end);
                return !subtitle.clientHeight || subtitle.scrollHeight <= subtitle.clientHeight + 1;
            }
            if (!ends.length) return text.length;
            // Poškozené OCR může obsahovat i jediné dlouhé slovo; nesmí se ztratit jeho konec.
            if (!fits(ends[0])) {
                var length = 0;
                ends = Array.from(text.slice(0, ends[0])).map(function (ch) { length += ch.length; return length; });
            }
            var low = 0, high = ends.length - 1, best = ends[0];
            while (low <= high) {
                var mid = Math.floor((low + high) / 2);
                if (fits(ends[mid])) { best = ends[mid]; low = mid + 1; }
                else high = mid - 1;
            }
            // Krátká dokončená věta má přednost před polovinou další věty.
            var part = text.slice(0, best), sentence = /[.!?…]["'”’»)]*(?=\s|$)/g, match, stop = 0;
            while ((match = sentence.exec(part))) stop = match.index + match[0].length;
            return stop >= best / 2 ? stop : best;
        }

        function nextCaption(now) {
            var rest = captionPacket.text.slice(captionOffset);
            captionOffset += rest.length - rest.replace(/^\s+/, "").length;
            rest = captionPacket.text.slice(captionOffset);
            var start = captionOffset;
            captionStart = start;
            var end = pageEnd(rest);
            var colors = (captionPacket.colors || []).slice(captionPacket.text.slice(0, start).split("\n").length - 1);
            if (start && captionPacket.text[start - 1] !== "\n") colors[0] = 0;
            paintCaption(rest.slice(0, end), colors);
            captionOffset += end;
            var letters = Array.from(rest.slice(0, end).normalize("NFC")).length;
            var storyDuration = captionPacket.adjustableTiming ?
                Math.ceil(Math.max(1500, Math.ceil(letters * 1000 / 18)) * captionPacket.durationPercent * 80 / 10000) :
                Math.max(1800, Math.ceil(letters * 1000 / 14));
            captionNext = now + (captionPacket.kind === "story" ? storyDuration : Math.max(2000, 1000 + end * 50));
        }

        function positionFrame(node, area) {
            node.style.left = (area[0] * 100) + "%";
            node.style.top = (area[1] * 100) + "%";
            node.style.width = ((area[2] - area[0]) * 100) + "%";
            node.style.height = ((area[3] - area[1]) * 100) + "%";
        }

        function positionChoices(area) {
            var leftRoom = area[0] - 0.02;
            var rightRoom = 1 - area[2] - 0.02;
            var width = Math.min(0.42, Math.max(leftRoom, rightRoom));
            var left = leftRoom >= rightRoom ? area[0] - width - 0.01 : area[2] + 0.01;
            var top = area[1];
            if (width < 0.2) {
                // Široký dokument nemá místo vedle; překlad zůstane v jeho zvolené oblasti.
                width = Math.min(0.8, Math.max(0.25, area[2] - area[0]));
                left = Math.min(area[0], 1 - width);
            }
            choices.style.left = (Math.max(0, left) * 100) + "%";
            choices.style.width = (width * 100) + "%";
            choices.style.maxHeight = "90vh";
            var viewport = document.documentElement.clientHeight || 1080;
            var height = Math.min(0.9, choices.scrollHeight / viewport || 0);
            choices.style.top = (Math.max(0.01, Math.min(top, 0.99 - height)) * 100) + "%";
        }

        this.handle = function (packet, now) {
            if (packet.kind === "ocr") {
                positionFrame(blue, packet.area);
                blue.hidden = !packet.visible;
                blueUntil = now + 8000;
                return true;
            }
            if (packet.kind !== "choices") return false;
            var changed = packet.id !== lastChoiceId;
            if (changed || packet.frame !== lastFrameMode) {
                positionFrame(yellow, packet.area);
                yellow.hidden = packet.frame === 0;
                yellowUntil = packet.frame === 2 ? Infinity : now + 8000;
                choicesUntil = packet.frame > 0 ? Infinity : now + (packet.selected === -2 ? 300000 : 45000);
            }
            lastFrameMode = packet.frame;
            if (!changed) return true;
            lastChoiceId = packet.id;
            choices.textContent = "";
            packet.items.forEach(function (text, index) {
                var row = document.createElement("div");
                row.textContent = text;
                row.className = "choice" + (index === packet.selected ? " selected" : "");
                choices.appendChild(row);
            });
            choices.hidden = packet.items.length === 0;
            positionChoices(packet.area);
            return true;
        };

        function paintCaption(text, colors) {
            // text v obalu <span class="text">, aby černé pozadí obepínalo jen řádky
            var obal = document.createElement("span");
            obal.className = "text";
            subtitle.textContent = "";
            subtitle.appendChild(obal);
            obal.textContent = text;
            if (colors.some(function (color) { return color !== 0; })) {
                obal.textContent = "";
                text.split("\n").forEach(function (line, index) {
                    if (index) obal.appendChild(document.createTextNode("\n"));
                    var colon = line.indexOf(":");
                    var color = colors[index] || 0;
                    if (color && colon >= 1 && colon <= 25) {
                        var name = document.createElement("span");
                        name.textContent = line.slice(0, colon + 1);
                        var argb = color >>> 0;
                        name.style.color = "rgba(" + [(argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) / 255].join(",") + ")";
                        obal.appendChild(name);
                        obal.appendChild(document.createTextNode(line.slice(colon + 1)));
                    } else obal.appendChild(document.createTextNode(line));
                });
            }
        }

        this.storyActive = function () { return storyMode; };
        this.oldStory = function (id) {
            var old = storyId && storyId.split("."), next = id.split(".");
            if (old && old[0] === next[0]) {
                for (var i = 1; i < 4; i++) {
                    if (Number(next[i]) < Number(old[i])) return true;
                    if (Number(next[i]) > Number(old[i])) return false;
                }
            }
            return false;
        };
        this.pauseStory = function (now) {
            if (storyMode && !storyPaused && !storyDone) {
                storyRemaining = Math.max(0, captionNext - now); storyPaused = true;
            }
            subtitle.hidden = true;
        };
        this.story = function (packet, now) {
            if (storyId !== packet.id) {
                if (this.oldStory(packet.id)) return null;
                captionPacket = null; storyId = packet.id; storyDone = !packet.text; storyMode = Boolean(packet.text); storyPaused = false;
                if (storyDone) { subtitle.hidden = true; return "done"; }
                this.caption(packet, now); captionUntil = Infinity;
            }
            if (captionPacket) {
                captionPacket.durationPercent = packet.durationPercent;
                captionPacket.adjustableTiming = packet.adjustableTiming;
            }
            if (captionPacket && JSON.stringify(captionPacket.layout) !== JSON.stringify(packet.layout)) {
                captionPacket.layout = packet.layout; captionOffset = captionStart;
                var hidden = subtitle.hidden; subtitle.hidden = false;
                nextCaption(now); storyRemaining = captionNext - now; subtitle.hidden = hidden;
            }
            if (!storyDone && packet.paused !== storyPaused) {
                if (packet.paused) storyRemaining = Math.max(0, captionNext - now);
                else captionNext = now + storyRemaining;
                storyPaused = packet.paused;
            }
            subtitle.hidden = storyDone || storyPaused;
            return storyDone ? "done" : "reading";
        };
        this.caption = function (packet, now) {
            var same = captionPacket && packet.text === captionPacket.text &&
                JSON.stringify([packet.layout, packet.colors]) === JSON.stringify([captionPacket.layout, captionPacket.colors]) && now < captionUntil;
            captionPacket = packet;
            captionUntil = now + packet.duration;
            if (!same) { captionOffset = 0; nextCaption(now); }
            subtitle.style.outline = packet.editing ? "4px dashed #78e89b" : "";
            subtitle.className = packet.transparent ? "pruhledne" : "";
            greenUntil = now + 8000;
        };

        this.tick = function (now) {
            if (captionPacket && (!storyMode || !storyPaused) && now < captionUntil && now >= captionNext) {
                if (captionOffset < captionPacket.text.trimEnd().length) nextCaption(now);
                else if (storyMode) { storyDone = true; captionPacket = null; subtitle.hidden = true; }
            }
            if (now >= choicesUntil) choices.hidden = true;
            if (now >= yellowUntil) yellow.hidden = true;
            if (now >= blueUntil) blue.hidden = true;
            if (now >= greenUntil) subtitle.style.outline = "";
        };

        this.clear = function () {
            // Při restartu UI se nedočtená replika musí přečíst znovu, nikoli falešně potvrdit.
            if (storyMode && !storyDone) storyId = null;
            choices.hidden = true; yellow.hidden = true; blue.hidden = true;
            choices.textContent = "";
            subtitle.style.outline = "";
            lastChoiceId = null; lastFrameMode = 0;
            choicesUntil = yellowUntil = blueUntil = greenUntil = 0;
            captionPacket = null; captionOffset = captionNext = captionUntil = 0;
            storyMode = false; storyDone = true; storyPaused = false;
        };
    };
}());
