/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Český titulek a žlutý úkol mise se nesmí překrývat.
   Úkol zůstává dole, titulek se posune nad něj (s mezerou). Výchozí poloha titulku je ta, kterou
   nastavil tablet (app.js ji ukládá do subtitle.zakladBottom); když úkol nesvítí, titulek se na ni vrátí. */
(function () {
    "use strict";
    window.PlayCaptionRozlozeni = function (subtitle, hint) {
        if (!subtitle || !hint) return;
        var zaklad = subtitle.zakladBottom || "";
        var bottom = zaklad;
        if (!hint.hidden && hint.textContent) {
            var obdelnik = hint.getBoundingClientRect();
            // skrytý výstup (lokální test, dočasné skrytí) má nulové rozměry → nic neměřit
            if (!obdelnik || !obdelnik.height) return;
            var vyska = window.innerHeight || document.documentElement.clientHeight;
            var zakladPx = zaklad.indexOf("%") > 0 ? vyska * parseFloat(zaklad) / 100 :
                (zaklad ? parseFloat(zaklad) : vyska * 0.09);
            var nadUkolem = Math.ceil(vyska - obdelnik.top + 12);
            bottom = Math.max(Math.round(zakladPx), nadUkolem) + "px";
        }
        if (subtitle.style.bottom !== bottom) subtitle.style.bottom = bottom;
    };
})();
