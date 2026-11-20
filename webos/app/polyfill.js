/* Doplňky pro starou LG (webOS 3.x, Chromium 38), která tyto funkce nezná. */
(function () {
    function def(obj, name, fn) {
        if (!obj[name]) Object.defineProperty(obj, name, { value: fn, configurable: true, writable: true });
    }
    def(Array.prototype, "find", function (fn, t) {
        for (var i = 0; i < this.length; i++) if (fn.call(t, this[i], i, this)) return this[i];
        return undefined;
    });
    def(Array.prototype, "findIndex", function (fn, t) {
        for (var i = 0; i < this.length; i++) if (fn.call(t, this[i], i, this)) return i;
        return -1;
    });
    def(Array, "from", function (src, map, t) {
        var out;
        if (typeof src === "string") out = src.match(/[\uD800-\uDBFF][\uDC00-\uDFFF]|[\s\S]/g) || [];
        else if (src && typeof src.length === "number") out = Array.prototype.slice.call(src);
        else if (src && typeof src.forEach === "function") { out = []; src.forEach(function (v) { out.push(v); }); }
        else out = [];
        return map ? out.map(map, t) : out;
    });
    def(String.prototype, "trimEnd", function () { return String(this).replace(/[\s﻿\xA0]+$/, ""); });
    def(String.prototype, "trimStart", function () { return String(this).replace(/^[\s﻿\xA0]+/, ""); });
    def(String.prototype, "includes", function (s, p) { return String(this).indexOf(s, p || 0) !== -1; });
    def(String.prototype, "startsWith", function (s, p) { p = p || 0; return String(this).substr(p, s.length) === s; });
    def(String.prototype, "endsWith", function (s) { var t = String(this); return t.slice(t.length - s.length) === s; });
    def(Array.prototype, "includes", function (v) { return this.indexOf(v) !== -1; });
})();
