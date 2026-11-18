const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const parse = require('./service/protocol').parse;
const app = JSON.parse(fs.readFileSync(__dirname + '/app/appinfo.json', 'utf8'));
assert.equal(app.title, 'PlayCaption LG');
assert.equal(app.version, JSON.parse(fs.readFileSync(__dirname + '/service/package.json', 'utf8')).version);
for (const [name, size] of [[app.icon, 80], [app.largeIcon, 130]]) {
    const png = fs.readFileSync(__dirname + '/app/' + name);
    assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    assert.equal(png.readUInt32BE(16), size);
    assert.equal(png.readUInt32BE(20), size);
}
assert.doesNotMatch(fs.readFileSync(__dirname + '/app/app.js', 'utf8'), /tablet/i);
const packet = (s) => parse(Buffer.from(s));
assert.equal(packet('OTHER\t1\t6000\ttext\t'), null);
assert.equal(packet('PCT1\tbad\t6000\ttext\t'), null);
assert.equal(packet('PCT1\t1\t6000'), null);
assert.equal(packet('x'.repeat(8193)), null);
assert.equal(packet('PCT1\t1\t6000\t' + 'x'.repeat(4097) + '\t'), null);
assert.equal(packet('PCT1\t1\t6000\tt\t' + 'x'.repeat(2049)), null);
assert.equal(packet('PCT1\t1\tInfinity\tt\t').duration, 6000);
const sample = packet('PCT1\t9223372036854775807\t6000\tPříliš žluťoučký\\nDruhý řádek\\\\n\tNápověda\t0.5\t0.9\t0.8\t1.2\tB:-1');
assert.equal(sample.id, '9223372036854775807');
assert.equal(sample.text, 'Příliš žluťoučký\nDruhý řádek\\n');
assert.deepEqual(sample.layout, [0.5, 0.9, 0.8, 1.2]);
assert.equal(packet('PCT1\t1\t0\tt\t').duration, 6000);
assert.equal(packet('PCT1\t1\t999999999999\tt\t').duration, 60000);
assert.equal(packet('PCT1\t1\t6000\tt\t\tNaN\t0.9\t0.8\t1').layout, null);
assert.equal(packet('PCT1\t1\t6000\tt\t\tB:123').layout, null);
assert.deepEqual(packet('PCT1\t1\t6000\tt\t\t-10\t20\t100\t100').layout, [0, 1, 1, 2.5]);

// Ověření UI se skutečnými pakety Android protokolu, včetně opakování a obnovení spojení.
const elements = Object.fromEntries(['status', 'subtitle', 'hint'].map(id => [id, { style: {}, textContent: '' }]));
const listeners = {};
const timers = new Map();
const bridges = [];
let now = 1000, nextId = 0, closed = 0;
function Bridge() { bridges.push(this); this.call = uri => { this.uri = uri; }; this.cancel = () => { this.cancelled = true; }; }
const receiverBridge = () => bridges.filter(b => b.uri.endsWith('/listen')).at(-1);
const activityBridges = () => bridges.filter(b => b.uri.endsWith('/restartUserActivityTimer'));
vm.runInNewContext(fs.readFileSync(__dirname + '/app/app.js', 'utf8'), {
    document: { getElementById: id => elements[id], addEventListener: (name, fn) => { listeners[name] = fn; } },
    window: { close: () => { closed++; }, addEventListener: (name, fn) => { listeners[name] = fn; } },
    PalmServiceBridge: Bridge, Date: { now: () => now },
    setInterval: fn => { timers.set(++nextId, fn); return nextId; }, clearInterval: id => timers.delete(id)
});
const receive = p => receiverBridge().onservicecallback(JSON.stringify({ returnValue: true, source: '192.168.0.2', packet: p }));
const tick = () => [...timers.values()].forEach(fn => fn());
now += 11000; tick();
assert.match(elements.status.textContent, /nepotvrdila spuštění příjmu/);
receiverBridge().onservicecallback(JSON.stringify({ returnValue:true, ready:false }));
assert.match(elements.status.textContent, /nepotvrdila spuštění příjmu/);
receiverBridge().onservicecallback(JSON.stringify({ returnValue:true, ready:true, networkReady:false }));
assert.match(elements.status.textContent, /síť/);
receiverBridge().onservicecallback(JSON.stringify({ returnValue: true, ready: true }));
assert.match(elements.status.textContent, /Čekám na zařízení/);
receiverBridge().onservicecallback(JSON.stringify({ returnValue: true, ready: true, name: 'LG PlayCaption · ABCDEF123456' }));
assert.match(elements.status.textContent, /LG PlayCaption · ABCDEF123456/);
receive(sample);
assert.equal(elements.subtitle.textContent, sample.text);
assert.equal(elements.subtitle.hidden, false);
assert.equal(elements.status.hidden, true);
assert.equal(elements.subtitle.style.fontSize, '45.6px');
now += 6100; tick();
assert.equal(elements.subtitle.hidden, true);
receive(sample); // stejné ID po výpadku musí obnovit text
assert.equal(elements.subtitle.hidden, false);
for (let i = 0; i < 7; i++) { now += 1000; receive(sample); tick(); }
assert.equal(elements.subtitle.hidden, true, 'Heartbeat nesmí obnovovat prošlý titulek.');
receive({ ...sample, id: '2', text: '<img src=x onerror=alert(1)>' });
assert.equal(elements.subtitle.textContent, '<img src=x onerror=alert(1)>');
assert.equal(elements.subtitle.hidden, false);
receive({ ...sample, id: '3', text: '', hint: '' });
assert.equal(elements.subtitle.hidden, true);
assert.equal(elements.hint.hidden, true);
now += 5100; tick();
assert.equal(elements.status.hidden, false);
assert.match(elements.status.textContent, /čekám na zařízení/);
receive(sample);
assert.equal(elements.subtitle.hidden, false);
const oldActivity = activityBridges().at(-1);
const staleActivityCallback = oldActivity.onservicecallback;
now += 45000; tick();
const currentActivity = activityBridges().at(-1);
staleActivityCallback('{"returnValue":false}');
assert.equal(elements.status.textContent.includes('LG odmítá'), false, 'Opožděná odpověď zrušeného volání nesmí měnit UI.');
assert.equal(oldActivity.cancelled, true);
currentActivity.onservicecallback('{"returnValue":false}');
receive({ ...sample, id: '4' });
assert.equal(elements.status.hidden, false, 'Nový titulek nesmí skrýt chybu prodloužení overlay.');
assert.match(elements.status.textContent, /LG odmítá/);
now += 45000; tick();
activityBridges().at(-1).onservicecallback('{"returnValue":true}');
assert.equal(elements.status.textContent.includes('LG odmítá'), false);
receive({ ...sample, id: '5' });
assert.equal(elements.status.hidden, true);
const pings = activityBridges().length;
for (let i = 0; i < 3; i++) { now += 45000; tick(); }
assert.equal(activityBridges().length, pings + 3);
assert.equal(closed, 0, 'Přijímač se nesmí po 120 sekundách sám zavřít.');
activityBridges().at(-1).onservicecallback('{"returnValue":false}');
assert.match(elements.status.textContent, /LG odmítá/);
listeners.webOSRelaunch();
assert.equal(bridges[0].cancelled, true);
assert.equal(timers.size, 1);
receiverBridge().onservicecallback(JSON.stringify({ returnValue: false, errorText: 'EADDRINUSE' }));
assert.match(elements.status.textContent, /EADDRINUSE/);
assert.equal(elements.subtitle.hidden, true);
listeners.keydown({ keyCode: 461, preventDefault() {} });
assert.equal(closed, 1);
assert.equal(timers.size, 0);
assert.equal(receiverBridge().cancelled, true);
assert.equal(activityBridges().at(-1).cancelled, true);

// Služba: discovery, validace, forwarding a ukončení UDP při zrušení posledního odběru.
const handlers = {}, sockets = [], sent = [], replies = [], serviceTimers = new Map();
let network = { eth0: [{ internal: false, family: 'IPv4', address: '192.168.0.9', netmask: '255.255.255.0', mac: '00:11:22:33:44:55' }] };
function Service() { this.register = (name, request, cancel) => { handlers[name] = { request, cancel }; }; }
const dgram = { createSocket() {
    const s = new EventEmitter();
    s.bind = (port, address, fn) => { assert.equal(port, 41989); s.bound = fn; };
    s.setBroadcast = value => { assert.equal(value, true); };
    s.send = (data, port, address, callback) => { sent.push({ data: data.toString(), port, address }); callback(null); };
    s.close = () => { s.closed = true; };
    sockets.push(s); return s;
} };
vm.runInNewContext(fs.readFileSync(__dirname + '/service/service.js', 'utf8'), {
    require(name) {
        if (name === 'webos-service') return Service;
        if (name === 'dgram') return dgram;
        if (name === 'os') return { hostname: () => 'LG', networkInterfaces: () => network };
        return require(name.startsWith('./') ? './service/' + name.slice(2) : name);
    }, Buffer, console, Date: { now: () => now },
    setInterval: fn => { serviceTimers.set(++nextId, fn); return nextId; }, clearInterval: id => serviceTimers.delete(id),
    setTimeout: fn => { const id = ++nextId; serviceTimers.set(id, () => { serviceTimers.delete(id); fn(); }); return id; },
    clearTimeout: id => serviceTimers.delete(id)
});
const request = { isSubscription: true, uniqueToken: 'a', respond: p => replies.push(p) };
handlers.listen.request(request);
sockets[0].bound();
assert.equal(replies.at(-1).ready, true);
assert.match(sent[0].data, /^PLAYCAPTION_TV\tlg-[0-9a-f]{12}\tLG PlayCaption · [0-9A-F]{12}\t0$/);
assert.equal(replies.at(-1).name, sent[0].data.split('\t')[2]);
assert.equal(sent[0].port, 41988);
assert.equal(sent[0].address, '192.168.0.255');
sockets[0].emit('message', Buffer.from('PCT1\t1\t6000\tČeský text\t'), { address: '192.168.0.2' });
assert.equal(replies.at(-1).packet.text, 'Český text');
now += 1000;
sockets[0].emit('message', Buffer.from('PCT1\t2\t6000\tJiný zdroj\t'), { address: '192.168.0.3' });
assert.equal(replies.at(-1).otherSender, true);
now += 1000;
sockets[0].emit('message', Buffer.from('PCT1\t2\t6000\tPůvodní zdroj\t'), { address: '192.168.0.2' });
assert.equal(replies.at(-1).packet.text, 'Původní zdroj');
const ownCount = replies.length;
sockets[0].emit('message', Buffer.from('PCQ1\t'+'a'.repeat(32)+'.0.0.0'), { address: '192.168.0.4' });
assert.equal(replies.length,ownCount,'Cizí nepodporovaný režim nesmí překrýt živý zdroj.');
now += 4999;
sockets[0].emit('message', Buffer.from('PCT1\t3\t6000\tCizí heartbeat\t'), { address: '192.168.0.3' });
assert.notEqual(replies.at(-1).packet?.text, 'Cizí heartbeat');
now += 1;
sockets[0].emit('message', Buffer.from('PCT1\t4\t6000\tPřevzetí po výpadku\t'), { address: '192.168.0.3' });
assert.equal(replies.at(-1).packet.text, 'Převzetí po výpadku');
const storyCount = replies.length;
const sentBeforeStory = sent.length;
now += 10000;
const storyId = 'a'.repeat(32)+'.0.1.0';
sockets[0].emit('message', Buffer.from('PCS1\tZADOST'), {address:'192.168.0.4',port:40000});
assert.equal(replies.length,storyCount,'Neplatné Story nesmí obsadit příjem.');
sockets[0].emit('message', Buffer.from('PCQ1\t'+storyId), {address:'192.168.0.4',port:40000});
assert.equal(sent.at(-1).data,'PCA1\t'+storyId+'\tready');
assert.equal(sent.length,sentBeforeStory+1);
// Dotaz na schopnosti nesmí přebrat ani prodloužit vlastnictví zdroje.
sockets[0].emit('message', Buffer.from('PCT1\t5\t6000\tStandardní fallback\t'), {address:'192.168.0.2'});
assert.equal(replies.at(-1).packet.text,'Standardní fallback');
const leased = replies.length;
sockets[0].emit('message', Buffer.from('PCS1\t'+storyId+'\t0\tCizí Story\t0.5\t0.9\t0.8\t1\t'), {address:'192.168.0.4',port:40000});
assert.equal(replies.at(-1).otherSender,true,'Cizí Story nesmí obejít aktivní zdroj.');
assert.equal(sent.length,sentBeforeStory+1,'Cizímu zdroji neposílat ACK.');
now += 5000;
sockets[0].emit('message', Buffer.from('PCS1\t'+storyId+'\t0\tStory\t0.5\t0.9\t0.8\t1\t'), {address:'192.168.0.4',port:40000});
assert.equal(replies.at(-1).packet.kind,'story');
now += 5000;
sockets[0].emit('message', Buffer.from('PCT1\t6\t6000\tStandard po Story\t'), {address:'192.168.0.2'});
let staleAck;
handlers.storyAck.request({payload:{id:storyId,state:'done'},respond:r=>staleAck=r});
assert.equal(staleAck.returnValue,false,'Po změně zdroje neodesílat ACK původnímu Story.');
const count = replies.length;
sockets[0].emit('message', Buffer.from('PCS1\tZADOST'), {});
assert.equal(replies.length, count, 'Přijímač nesmí předávat přihlašovací údaje.');
handlers.listen.request({ ...request, uniqueToken: 'b' });
assert.equal(sockets.length, 1);
handlers.listen.cancel(request);
assert.equal(sockets[0].closed, undefined);
handlers.listen.cancel({ uniqueToken: 'b' });
assert.equal(sockets[0].closed, true);
assert.equal(serviceTimers.size, 0);
handlers.listen.request(request);
sockets[1].emit('error', { code: 'EADDRINUSE' });
assert.equal(replies.at(-1).returnValue, false);
assert.match(replies.at(-1).errorText, /EADDRINUSE/);
assert.equal(sockets[1].closed, true);
network = {
    wlan0: [{ internal: false, family: 'IPv4', address: '10.42.7.130', netmask: '255.255.255.128', mac: '00:11:22:33:44:66' }],
    eth0: [{ internal: false, family: 'IPv4', address: '172.20.16.4', netmask: '255.255.240.0', mac: '00:11:22:33:44:77' }],
    lo: [{ internal: true, family: 'IPv4', address: '127.0.0.1', netmask: '255.0.0.0' }],
    ipv6: [{ internal: false, family: 'IPv6', address: '::1' }]
};
const before = sent.length;
[...serviceTimers.values()].forEach(fn => fn());
handlers.listen.request(request); sockets[2].bound();
assert.deepEqual(sent.slice(before).map(p => p.address).sort(), ['10.42.7.255', '172.20.31.255']);
assert.notEqual(sent[before].data.split('\t')[1], sent[0].data.split('\t')[1], 'Jiná TV musí mít vlastní discovery ID.');
assert.notEqual(sent[before].data.split('\t')[2], sent[0].data.split('\t')[2], 'Více LG musí být rozlišitelných i v Android seznamu.');
assert.equal(replies.at(-1).name, sent[before].data.split('\t')[2]);
handlers.listen.cancel(request);
assert.equal(serviceTimers.size, 0);
network = { eth0: [{ internal: false, family: 'IPv4', address: '10.1.2.3', netmask: '255.255.0.0', mac: '00:11:22:33:44:55' }] };
handlers.listen.request(request); sockets[3].bound();
assert.equal(sent.at(-1).data, sent[0].data, 'Změna IP stejné TV nesmí měnit její identitu ani název.');
assert.equal(sent.at(-1).address, '10.1.255.255');
handlers.listen.cancel(request);
// Přijímač musí znovu najít síť i bez restartu služby.
network = {};
handlers.listen.request(request); sockets.at(-1).bound();
const lateSocket = sockets.at(-1), lateBefore = sent.length;
network = { wlan0: [{ internal:false, family:'IPv4', address:'192.168.7.9', netmask:'255.255.255.0', mac:'00:11:22:33:44:55' }] };
[...serviceTimers.values()].forEach(fn => fn());
assert.equal(sent.length, lateBefore + 1, 'Později připojená síť musí dostat discovery.');
assert.equal(sent.at(-1).address, '192.168.7.255');
const nonce = 'a'.repeat(32);
lateSocket.emit('message', Buffer.from('PLAYCAPTION_FIND\t'+nonce), {address:'192.168.7.2',port:45678});
assert.equal(sent.at(-1).address, '192.168.7.2');
assert.equal(sent.at(-1).port, 45678);
assert.equal(sent.at(-1).data.split('\t')[4], nonce);
network.wlan0[0].address = '10.9.1.2';
[...serviceTimers.values()].forEach(fn => fn());
assert.equal(sent.at(-1).address, '10.9.1.255');
handlers.listen.cancel(request);
console.log('OK: opožděné odpovědi, protokol/UI, síť po startu, změna IP, přímé discovery a ukončení služby.');

// Události starého socketu po novém odběru nesmí ovlivnit nový přijímač.
handlers.listen.request(request);
const staleSocket = sockets.at(-1);
handlers.listen.cancel(request);
handlers.listen.request(request);
const currentSocket = sockets.at(-1);
currentSocket.bound();
const sentAfterRestart = sent.length;
staleSocket.bound();
staleSocket.emit('error', {code:'EADDRINUSE'});
assert.equal(currentSocket.closed, undefined);
assert.equal(replies.at(-1).ready, true);
assert.equal(sent.length, sentAfterRestart);
handlers.listen.cancel(request);
assert.equal(serviceTimers.size, 0);
