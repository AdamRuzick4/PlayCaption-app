/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Toast režim služby: na TV bez overlaye přes externí HDMI (např. webOS Lite) se titulek
// ukáže jako systémový toast. Testuje se přes skutečné loopback UDP a mock notifikací.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const dgram = require('node:dgram');
const { once } = require('node:events');

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const sender = dgram.createSocket('udp4');
    const sockets = [], timers = new Set();
    const handlers = {};
    const toasts = [];            // zachycené zprávy createToast
    let now = 10000, replyToToast;
    const replies = [];
    let activityCreated = 0, activityCompleted = 0;
    const watchdog = setTimeout(() => { console.error('toast test timeout'); process.exit(1); }, 6000);

    function Service() {
        this.register = (name, request, cancel) => { handlers[name] = { request, cancel }; };
        // Mock luna sběrnice: zachytí createToast, ostatní volání jen potvrdí.
        this.call = (uri, params, cb) => {
            if (uri.indexOf('createToast') >= 0) {
                toasts.push(params && params.message);
                replyToToast = cb;
                if (!params.message.startsWith('Story')) cb({ payload: { returnValue: true, toastId: 't' + toasts.length } });
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
                    const send = socket.send.bind(socket);
                    socket.send = (data, ...args) => {
                        const [port, host, cb] = args.slice(-3);
                        if (port === 41988) { if (cb) cb(); return; }
                        replies.push([data.toString(), port, host]);
                        send(data, ...args);
                    };
                    sockets.push(socket);
                    return socket;
                } };
                return require(name.startsWith('./') ? './service/' + name.slice(2) : name);
            },
            Buffer, console, Date: { now: () => now },
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

        // Hodiny řídí test. Stejný paket Android znovu posílá i při ztrátě ACK.
        const session = 'a'.repeat(32);
        const wireId = session+'.1.0.0';
        async function onWire(frame) {
            const response = once(sender,'message');
            sender.send(Buffer.from(frame),port,'127.0.0.1');
            return (await response)[0].toString();
        }
        assert.equal(await onWire('PCQ1\t'+wireId),'PCA1\t'+wireId+'\tready');
        const frame = ['PCS1',wireId,'0','Krátká replika','0.5','0.9','0.8','1','','D:100'].join('\t');
        assert.equal(await onWire(frame),'PCA1\t'+wireId+'\treading');
        now+=1200;
        assert.equal(await onWire(frame),'PCA1\t'+wireId+'\tdone');
        const remote = {address:'127.0.0.1',port:sender.address().port};
        function story(n, text, paused=false) {
            const id = session+'.1.'+n+'.0';
            replies.length = 0;
            sockets[0].emit('message',Buffer.from(['PCS1',id,paused?'1':'0',text,'0.5','0.9','0.8','1','','D:100'].join('\t')),remote);
            if (!replies.length) return null;
            assert.equal(replies.length,1);
            const [ack,port,host] = replies[0];
            assert.equal(port,remote.port);
            assert.equal(host,remote.address);
            assert.equal(ack.split('\t')[1],id);
            return ack.split('\t')[2];
        }
        const accepted = () => replyToToast({payload:{returnValue:true,toastId:'story-toast'}});
        assert.notEqual(story(1,'Story A'),'done');
        now+=2000;assert.notEqual(story(1,'Story A'),'done','čekáme na skutečnou odpověď LG');
        accepted();
        now+=1199;assert.equal(story(1,'Story A'),'reading');
        now++;assert.equal(story(1,'Story A'),'done');
        assert.equal(story(1,'Story A'),'done');
        assert.equal(toasts.filter(t=>t==='Story A').length,1);
        story(2,'Story A'); // stejné znění, nová replika
        assert.equal(toasts.filter(t=>t==='Story A').length,2);
        const stale = replyToToast;
        story(3,'Story B');
        stale({payload:{returnValue:true,toastId:'old'}});
        replyToToast({payload:{returnValue:false}});
        now+=2000;assert.notEqual(story(3,'Story B'),'done');
        accepted();
        now+=500;assert.equal(story(3,'Story B',true),'reading');
        now+=3000;assert.notEqual(story(3,'Story B'),'done');
        accepted();now+=1200;assert.equal(story(3,'Story B'),'done');
        assert.equal(story(2,'Story A'),null,'opožděný paket nesmí přepsat novou repliku');
        story(4,'Story čeká');const pending = replyToToast;
        now+=5001;assert.notEqual(story(4,'Story čeká'),'done','chybějící odpověď se zkusí znovu');
        pending({payload:{returnValue:true}});
        now+=1200;assert.notEqual(story(4,'Story čeká'),'done','stará odpověď neodstartuje čtení');
        assert.equal(story(5,''),'done','zrušení prázdným paketem');
        const long = 'Story '+('dlouhá věta '.repeat(20)).trim();
        story(6,long);accepted();
        let state;
        for (let i=0;i<40;i++) {
            now+=1000;const count=toasts.length;
            state=story(6,long);
            if(toasts.length>count) accepted();
            if(state==='done') break;
        }
        assert.equal(state,'done');
        const start=toasts.findIndex(t=>t.startsWith('Story dlouhá'));
        assert.equal(toasts.slice(start).join(' '),long);
        handlers.listen.request({isSubscription:true,uniqueToken:'overlay',respond(){}});
        assert.equal(story(7,'Story živého překryvu'),null,'potvrzení patří překryvu');
        assert.ok(!toasts.includes('Story živého překryvu'));
        handlers.listen.cancel({uniqueToken:'overlay'});

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
