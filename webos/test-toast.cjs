/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Toast režim služby: na TV bez overlaye přes externí HDMI (např. webOS Lite) se titulek
// ukáže jako systémový toast. Testuje se přes skutečné loopback UDP a mock notifikací.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const dgram = require('node:dgram');

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const sender = dgram.createSocket('udp4');
    const sockets = [], timers = new Set();
    const handlers = {};
    const toasts = [];            // zachycené zprávy createToast
    let activityCreated = 0, activityCompleted = 0;
    const watchdog = setTimeout(() => { console.error('toast test timeout'); process.exit(1); }, 6000);

    function Service() {
        this.register = (name, request, cancel) => { handlers[name] = { request, cancel }; };
        // Mock luna sběrnice: zachytí createToast, ostatní volání jen potvrdí.
        this.call = (uri, params, cb) => {
            if (uri.indexOf('createToast') >= 0) {
                toasts.push(params && params.message);
                if (cb) cb({ payload: { returnValue: true, toastId: 't' + toasts.length } });
            } else if (cb) cb({ payload: { returnValue: true } });
        };
        this.activityManager = {
            create: (name, cb) => { activityCreated++; if (cb) cb('activity-' + activityCreated); },
            complete: (value, cb) => { activityCompleted++; if (cb) cb(); }
        };
    }

    try {
        vm.runInNewContext(fs.readFileSync(__dirname + '/service/service.js', 'utf8'), {
            require(name) {
                if (name === 'webos-service') return Service;
                if (name === 'os') return {
                    hostname: () => 'LG-test',
                    networkInterfaces: () => ({ test: [{ internal: false, family: 'IPv4', address: '127.0.0.1', netmask: '255.0.0.0', mac: '00:11:22:33:44:55' }] })
                };
                if (name === 'dgram') return { createSocket() {
                    const socket = dgram.createSocket('udp4');
                    const bind = socket.bind.bind(socket);
                    socket.bind = (port, host, callback) => { assert.equal(port, 41989); bind(0, '127.0.0.1', callback); };
                    // discovery broadcast spolkni, ať test nešumí do sítě
                    socket.send = (data, port, host, cb) => { if (cb) cb(); };
                    sockets.push(socket);
                    return socket;
                } };
                return require(name.startsWith('./') ? './service/' + name.slice(2) : name);
            },
            Buffer, console,
            setInterval(cb, ms) { const t = setInterval(cb, ms); timers.add(t); return t; },
            clearInterval(t) { clearInterval(t); timers.delete(t); }
        });

        // Zapnutí toast režimu nastartuje socket i keepalive aktivitu (bez UI odběratele).
        await new Promise((resolve) => handlers.toastMode.request({ payload: { on: true }, respond(r) {
            assert.equal(r.returnValue, true); assert.equal(r.toastMode, true); resolve();
        } }));
        assert.ok(activityCreated >= 1, 'toast režim vytvoří keepalive aktivitu');
        await delay(150); // počkej na bind socketu
        const port = sockets[0].address().port;

        // 1) Bez UI odběratele se titulek ukáže jako toast.
        sender.send(Buffer.from('PCT1\t1\t6000\tAhoj světe\tNápověda'), port, '127.0.0.1');
        await delay(120);
        assert.deepEqual(toasts, ['Ahoj světe'], 'bez overlaye se titulek ukáže jako toast');

        // 2) Když žije UI overlay (odběratel), toast se neposílá – titulek řeší overlay.
        handlers.listen.request({ isSubscription: true, uniqueToken: 'ui', respond() {} });
        sender.send(Buffer.from('PCT1\t2\t6000\tDruhá replika\t'), port, '127.0.0.1');
        await delay(120);
        assert.deepEqual(toasts, ['Ahoj světe'], 's živým overlayem se toast neduplikuje');

        // 3) Odběratel pryč → zase toasty; stejný text se do 4 s neopakuje.
        handlers.listen.cancel({ uniqueToken: 'ui' });
        sender.send(Buffer.from('PCT1\t3\t6000\tTřetí\t'), port, '127.0.0.1');
        await delay(120);
        sender.send(Buffer.from('PCT1\t4\t6000\tTřetí\t'), port, '127.0.0.1');
        await delay(120);
        assert.deepEqual(toasts, ['Ahoj světe', 'Třetí'], 'stejný text se do 4 s neopakuje');

        // 4) Vypnutí toast režimu dokončí aktivitu a (bez odběratele) zavře socket.
        const closed = new Promise((resolve) => sockets[0].once('close', resolve));
        await new Promise((resolve) => handlers.toastMode.request({ payload: { on: false }, respond() { resolve(); } }));
        await closed;
        assert.ok(activityCompleted >= 1, 'vypnutí dokončí keepalive aktivitu');
        assert.equal(timers.size, 0, 'po zavření neběží žádný časovač');
        console.log('OK: toast režim – toast bez overlaye, hybrid s overlayem, dedup, keepalive aktivita a zavření.');
    } finally {
        clearTimeout(watchdog);
        timers.forEach(clearInterval);
        for (const socket of [...sockets, sender]) {
            try { socket.close(); } catch (error) { if (error.code !== 'ERR_SOCKET_DGRAM_NOT_RUNNING') throw error; }
        }
    }
})().catch((error) => { console.error(error); process.exitCode = 1; });
