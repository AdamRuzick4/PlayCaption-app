/* SPDX-License-Identifier: AGPL-3.0-or-later */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const parse = require('./service/protocol').parse;

function clock() {
    let now = 1000, next = 0;
    const jobs = new Map();
    const add = (fn, ms, interval) => { jobs.set(++next, { fn, at: now + ms, interval }); return next; };
    return {
        get now() { return now; }, jobs,
        Date: { now: () => now },
        setTimeout: (fn, ms) => add(fn, ms, 0), clearTimeout: id => jobs.delete(id),
        setInterval: (fn, ms) => add(fn, ms, ms), clearInterval: id => jobs.delete(id),
        advance(ms) {
            const end = now + ms;
            let turns = 0;
            while (true) {
                const entry = [...jobs].filter(([, j]) => j.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
                if (!entry) break;
                assert.ok(++turns < 10000, 'Časovače se nesmějí roztočit bez prodlevy.');
                const [id, job] = entry; now = job.at;
                if (job.interval) job.at += job.interval; else jobs.delete(id);
                job.fn();
            }
            now = end;
        }
    };
}

function service() {
    const time = clock(), handlers = {}, sockets = [], replies = [], sent = [];
    const state = { failCreate: false, failBind: false, network: { eth0: [{ internal: false, family: 'IPv4',
        address: '192.168.0.9', netmask: '255.255.255.0', mac: '00:11:22:33:44:55' }] } };
    function Service() { this.register = (name, request, cancel) => { handlers[name] = { request, cancel }; }; }
    vm.runInNewContext(fs.readFileSync(__dirname + '/service/service.js', 'utf8'), {
        ...time, Buffer, console,
        require(name) {
            if (name === 'webos-service') return Service;
            if (name === 'os') return { hostname: () => 'LG', networkInterfaces: () => state.network };
            if (name === 'dgram') return { createSocket() {
                if (state.failCreate) { state.failCreate = false; throw Object.assign(new Error(), { code: 'EMFILE' }); }
                const s = new EventEmitter();
                s.bind = (port, host, fn) => {
                    assert.equal(port, 41989);
                    if (state.failBind) { state.failBind = false; throw Object.assign(new Error(), { code: 'EADDRINUSE' }); }
                    s.bound = fn;
                };
                s.setBroadcast = () => {};
                s.send = (data, port, address, fn) => { sent.push({ data: data.toString(), port, address }); fn(null); };
                s.close = () => { s.closed = true; s.emit('close'); };
                sockets.push(s); return s;
            } };
            return require(name.startsWith('./') ? './service/' + name.slice(2) : name);
        }
    });
    return { time, sockets, replies, sent, state, handlers,
        listen(id = 'a') { handlers.listen.request({ isSubscription: true, uniqueToken: id, respond: p => replies.push(p) }); },
        cancel(id = 'a') { handlers.listen.cancel({ uniqueToken: id }); }
    };
}

// Výpadek i neúspěšné bind: jeden pokus, rostoucí prodleva, obnova se stejným protokolem.
{
    const h = service(); h.listen(); h.sockets[0].bound();
    const old = h.sockets[0], identity = h.sent[0].data;
    old.emit('error', { code: 'ENETDOWN' });
    assert.equal(old.closed, true);
    h.time.advance(999); assert.equal(h.sockets.length, 1);
    h.time.advance(1); assert.equal(h.sockets.length, 2, 'UDP příjem se musí automaticky obnovit.');
    h.sockets[1].emit('error', { code: 'EADDRINUSE' });
    h.time.advance(1999); assert.equal(h.sockets.length, 2);
    h.time.advance(1); assert.equal(h.sockets.length, 3);
    h.sockets[2].bound();
    assert.equal(h.sent.at(-1).data, identity);
    const count = h.replies.length;
    old.bound(); old.emit('error', { code: 'OLD' });
    old.emit('message', Buffer.from('PCT1\t9\t6000\tStarý socket\t'), { address: '192.168.0.2' });
    assert.equal(h.replies.length, count, 'Opožděné události starého socketu se ignorují.');
    const id = 'a'.repeat(32) + '.1.1.0', peer = { address: '192.168.0.2', port: 42000 };
    const send = text => h.sockets[2].emit('message', Buffer.from(text), peer);
    send('PCQ1\t' + id); assert.equal(h.sent.at(-1).data, 'PCA1\t' + id + '\tready');
    send('PCS1\t' + id + '\t0\tPříběh po obnově.\t0.5\t0.9\t0.8\t1\t');
    h.handlers.storyAck.request({ payload: { id, state: 'reading' }, respond: r => assert.equal(r.returnValue, true) });
    assert.equal(h.sent.at(-1).data, 'PCA1\t' + id + '\treading');
    send('PCT1\t10\t6000\tStandardní titulek\t');
    assert.equal(h.replies.at(-1).packet.text, 'Standardní titulek');
    h.cancel(); assert.equal(h.time.jobs.size, 0);
}

// Síť se vrací s novou IP; stav služby má pravidelný heartbeat i bez herních titulků.
{
    const h = service(); h.listen(); h.sockets[0].bound();
    const identity = h.sent[0].data, count = h.replies.length;
    h.time.advance(2000); assert.ok(h.replies.length > count, 'Živá služba se pravidelně ozývá UI.');
    h.state.network = {}; h.time.advance(2000);
    assert.equal(h.replies.at(-1).networkReady, false);
    h.state.network.eth0 = [{ internal: false, family: 'IPv4', address: '10.42.7.130',
        netmask: '255.255.255.128', mac: '00:11:22:33:44:55' }];
    h.time.advance(2000);
    assert.equal(h.sent.at(-1).address, '10.42.7.255');
    assert.equal(h.sent.at(-1).data, identity);
    assert.equal(h.replies.at(-1).networkReady, true);
    h.sockets[0].emit('close'); h.time.advance(1000);
    assert.equal(h.sockets.length, 2, 'Obnoví se i socket zavřený bez error.');
    h.cancel(); h.time.advance(60000); assert.equal(h.sockets.length, 2);
    assert.equal(h.time.jobs.size, 0);
}

// Ani synchronní chyba vytvoření soketu nesmí ukončit službu; odhlášení ruší čekající opakování.
{
    const h = service(); h.state.failCreate = true;
    assert.doesNotThrow(() => h.listen());
    h.listen('b'); h.time.advance(1000); assert.equal(h.sockets.length, 1);
    h.sockets[0].emit('error', { code: 'EADDRINUSE' });
    h.cancel(); h.time.advance(2000); assert.equal(h.sockets.length, 2, 'Další odběratel udržuje obnovu.');
    h.sockets[1].emit('error', { code: 'EADDRINUSE' });
    h.cancel('b'); h.time.advance(60000);
    assert.equal(h.sockets.length, 2); assert.equal(h.time.jobs.size, 0);
}

// Synchronní bind a dlouhý výpadek: prodleva má strop, během čekání nevznikají další sockety.
{
    const h = service(); h.state.failBind = true;
    assert.doesNotThrow(() => h.listen());
    for (const delay of [1000, 2000, 4000, 8000, 15000, 15000]) {
        const count = h.sockets.length;
        h.time.advance(delay - 1); assert.equal(h.sockets.length, count);
        h.time.advance(1); assert.equal(h.sockets.length, count + 1);
        h.sockets.at(-1).emit('error', { code: 'EADDRINUSE' });
        assert.equal(h.time.jobs.size, 1);
    }
    h.cancel(); assert.equal(h.time.jobs.size, 0);
}

class Element {
    constructor() { this.style = {}; this.hidden = true; this.children = []; this.value = ''; }
    set textContent(t) { this.value = String(t); this.children = []; }
    get textContent() { return this.value + this.children.map(c => c.textContent).join(''); }
    appendChild(c) { this.children.push(c); }
    get clientHeight() { return 40; }
    get scrollHeight() { return Math.ceil(Array.from(this.textContent).length / 22) * 20; }
}
function app() {
    const time = clock(), bridges = [], listeners = {}, state = { failListen: false };
    const elements = Object.fromEntries(['status', 'subtitle', 'hint', 'choices', 'choice-frame', 'ocr-frame'].map(id => [id, new Element()]));
    const document = { documentElement: { clientHeight: 1080 }, getElementById: id => elements[id],
        createElement: () => new Element(), createTextNode: text => ({ textContent: text }),
        addEventListener: (name, fn) => { listeners[name] = fn; } };
    const window = { addEventListener: (name, fn) => { listeners[name] = fn; }, close() {} };
    function Bridge() {
        bridges.push(this);
        this.call = (uri, payload) => {
            this.uri = uri; this.payload = payload;
            if (uri.endsWith('/listen') && state.failListen) throw new Error('Service unavailable');
        };
        this.cancel = () => { this.cancelled = true; };
    }
    vm.runInNewContext(fs.readFileSync(__dirname + '/app/receiver-ui.js', 'utf8'), { window });
    vm.runInNewContext(fs.readFileSync(__dirname + '/app/app.js', 'utf8'), { ...time, window, document, PalmServiceBridge: Bridge });
    const listens = () => bridges.filter(b => b.uri.endsWith('/listen'));
    const respond = payload => listens().at(-1).onservicecallback(JSON.stringify(payload));
    return { time, elements, bridges, listeners, listens, respond, state,
        ready() { respond({ returnValue: true, ready: true, networkReady: true }); },
        packet(text) { respond({ returnValue: true, source: '192.168.0.2', packet: parse(Buffer.from(text)) }); }
    };
}

// Zaniklá Luna služba může odmítnout call i cancel synchronně; další pokus musí přesto fungovat.
{
    const h = app(); h.ready(); h.state.failListen = true;
    h.listens()[0].cancel = () => { throw new Error('Already disconnected'); };
    h.respond({ returnValue: false, errorText: 'Service unavailable' });
    assert.doesNotThrow(() => h.time.advance(2000));
    assert.equal(h.listens().length, 2);
    h.state.failListen = false; h.time.advance(4000); h.ready();
    assert.equal(h.listens().length, 3);
    h.listeners.pagehide(); assert.equal(h.time.jobs.size, 0);
}

// Neodpovídající služba i tichá ztráta odběru: zrušit starý odběr, ignorovat opožděné callbacky.
{
    const h = app(), old = h.listens()[0], late = old.onservicecallback;
    h.time.advance(11999); assert.equal(h.listens().length, 1);
    h.time.advance(1); assert.equal(h.listens().length, 2, 'UI se znovu přihlásí i bez callbacku služby.');
    assert.equal(old.cancelled, true); h.ready();
    const status = h.elements.status.textContent;
    late(JSON.stringify({ returnValue: false, errorText: 'stará chyba' }));
    assert.equal(h.elements.status.textContent, status);
    for (let i = 0; i < 12; i++) { h.time.advance(2000); h.ready(); }
    assert.equal(h.listens().length, 2, 'Chybějící dialog není výpadek živé služby.');
    h.time.advance(12000); assert.equal(h.listens().length, 3, 'Zaniklý odběr se odhalí i po dřívějším ready.');
    h.listeners.pagehide(); h.time.advance(60000);
    assert.equal(h.listens().length, 3); assert.equal(h.time.jobs.size, 0);
}

// Opakované chybové odpovědi nesmí posouvat termín obnovy. Pozdější ready ji naopak ruší.
{
    const h = app(); h.ready();
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    h.time.advance(1000); h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    h.time.advance(1000); assert.equal(h.listens().length, 2);
    h.ready(); h.time.advance(2000); h.ready();
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    h.time.advance(1000); h.ready(); h.time.advance(2000);
    assert.equal(h.listens().length, 2, 'Samoobnovení služby nerestartuje již funkční odběr.');
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    h.listeners.pagehide(); h.time.advance(60000);
    assert.equal(h.listens().length, 2); assert.equal(h.time.jobs.size, 0);
}

// Story pokračuje zbývajícím časem stejné stránky, dočtenou repliku znovu nezobrazuje.
{
    const h = app(), id = 'b'.repeat(32) + '.1.1.0'; h.ready();
    const story = 'PCS1\t' + id + '\t0\tAno.\t0.5\t0.9\t0.8\t1\t0\tD:100';
    h.packet(story); h.time.advance(500);
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    assert.equal(h.elements.subtitle.hidden, true);
    h.time.advance(2000); h.ready(); h.packet(story);
    assert.equal(h.elements.subtitle.hidden, false);
    h.time.advance(700); assert.equal(h.elements.subtitle.hidden, false);
    // Zbývající čas vyprší v 4200 ms, vykreslovací tick přijde přesně v 4250 ms.
    h.time.advance(49); assert.equal(h.elements.subtitle.hidden, false);
    h.time.advance(1); assert.equal(h.elements.subtitle.hidden, true, 'Čas stránky se po obnově nespustí od začátku.');
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    h.time.advance(2000); h.ready(); h.packet(story);
    assert.equal(h.elements.subtitle.hidden, true);
    assert.ok(h.bridges.some(b => b.uri.endsWith('/storyAck') && JSON.parse(b.payload).state === 'done'));
    h.packet('PCS1\t' + 'b'.repeat(32) + '.2.0.0\t1\t\t0.5\t0.9\t0.8\t1\t');
    h.packet('PCT1\t1\t6000\tStandardní po obnově\t');
    assert.equal(h.elements.subtitle.textContent, 'Standardní po obnově');
    h.listeners.pagehide(); assert.equal(h.time.jobs.size, 0);
}

// Obnova uprostřed druhé stránky zachová pozici i volby; stejná PCV1 po návratu musí znovu fungovat.
{
    const h = app(), id = 'c'.repeat(32) + '.1.1.0'; h.ready();
    const story = 'PCS1\t' + id + '\t0\tPrvní stránka vypráví začátek. Druhá pokračuje v ději. Třetí přidává další slova, aby text měl několik úplných stránek.\t0.5\t0.9\t0.8\t1\t0\tD:100';
    h.packet(story); const first = h.elements.subtitle.textContent;
    for (let i = 0; i < 80 && h.elements.subtitle.textContent === first; i++) { h.time.advance(250); h.packet(story); }
    const second = h.elements.subtitle.textContent;
    assert.notEqual(second, first); assert.equal(h.elements.subtitle.hidden, false);
    const choice = 'PCV1\t1\t2\t0.1\t0.2\t0.8\t0.9\t0\tZůstat\\nOdejít';
    h.packet(choice); h.time.advance(250);
    h.respond({ returnValue: false, errorText: 'UDP ENETDOWN' });
    for (const id of ['choices', 'choice-frame', 'ocr-frame']) assert.equal(h.elements[id].hidden, true);
    h.time.advance(2000); h.ready(); h.packet(choice); h.packet(story);
    assert.equal(h.elements.subtitle.textContent, second, 'Ani PCV1 před Story nevrátí druhou stránku na začátek repliky.');
    assert.equal(h.elements.choices.hidden, false, 'Stejné ID volby po obnově není zahozeno jako starý heartbeat.');
    h.listeners.pagehide(); assert.equal(h.time.jobs.size, 0);
}

console.log('OK: simulované výpadky UDP/Luna, heartbeat, změna IP, obnova Story a zrušení všech opakování.');
