const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup() {
    let handler, activityCallback, completed = 0;
    const calls = [], replies = [], timers = new Map();
    let timerId = 0;
    const exports = {};
    vm.runInNewContext(fs.readFileSync(__dirname + '/service/switch-input.js', 'utf8'), {
        exports, setTimeout(fn) { timers.set(++timerId, fn); return timerId; }, clearTimeout(id) { timers.delete(id); }
    });
    exports.register({
        register(name, fn) { assert.equal(name, 'switchInput'); handler = fn; },
        call(uri, payload, fn) { calls.push({ uri, payload, fn }); },
        activityManager: {
            create(name, fn) { activityCallback = fn; },
            complete(activity) { assert.equal(activity.id, 1); completed++; }
        }
    });
    return {
        calls, replies, timers,
        request(id) { handler({ payload: { id }, respond: r => replies.push(r) }); },
        activity() { activityCallback({ id: 1 }); },
        get completed() { return completed; },
        respond(index, payload) { calls[index].fn({ payload }); }
    };
}

let s = setup();
s.request('com.webos.app.browser');
assert.equal(s.replies[0].returnValue, false);
assert.equal(s.calls.length, 0);
s = setup();
s.request('com.webos.app.hdmi4');
s.request('com.webos.app.hdmi2');
assert.match(s.replies[0].errorText, /probíhá/);
s.activity();
s.respond(0, { returnValue: true, devices: [{ appId: 'com.webos.app.hdmi4' }] });
assert.equal(s.calls[1].payload.id, 'com.webos.app.hdmi4');
s.respond(1, { returnValue: true });
assert.equal(s.calls.length, 2, 'Overlay čeká na ustálení HDMI.');
{ const [id, fn] = [...s.timers.entries()].at(-1); s.timers.delete(id); fn(); }
assert.equal(s.calls[2].payload.id, 'cz.playcaption.overlaytest');
assert.equal(s.calls[2].payload.params.resumeOverlay, true);
s.respond(2, { returnValue: true });
{ const [id, fn] = [...s.timers.entries()].at(-1); s.timers.delete(id); fn(); }
assert.equal(s.calls[3].payload.id, 'cz.playcaption.overlaytest');
assert.equal(s.calls[3].payload.params.resumeOverlay, true);
s.respond(3, { returnValue: true });
assert.equal(s.replies.at(-1).returnValue, true);
assert.equal(s.completed, 1);
assert.equal(s.timers.size, 0);
s = setup();
s.request('com.webos.app.hdmi2'); s.activity();
s.respond(0, { returnValue: true, devices: [{ appId: 'com.webos.app.hdmi1' }] });
assert.equal(s.calls.length, 1);
assert.equal(s.replies[0].returnValue, false);
assert.equal(s.completed, 1);
s = setup();
s.request('com.webos.app.hdmi2'); s.activity();
s.respond(0, { returnValue: false, errorText: 'denied' });
assert.equal(s.replies[0].errorText, 'denied');
assert.equal(s.completed, 1);
s = setup();
s.request('com.webos.app.hdmi2');
[...s.timers.values()][0]();
s.activity();
assert.equal(s.calls.length, 0, 'Opožděná aktivita nesmí přepnout vstup po timeoutu.');
assert.equal(s.completed, 1);
assert.equal(s.replies.length, 1);

// UI načte skutečné vstupy, použije uloženou volbu a při obnovení neotevře menu.
const nodes = {};
for (const id of ['status', 'subtitle', 'hint', 'inputs', 'input-options', 'input-status', 'input-current']) {
    nodes[id] = { style: {}, hidden: true, textContent: '', appendChild(button) { this.children.push(button); }, children: [], focus() { this.focused = true; } };
}
const bridges = [], listeners = {};
function Bridge() {
    bridges.push(this);
    this.call = (uri, payload) => { this.uri = uri; this.payload = JSON.parse(payload); };
    this.cancel = () => {};
}
vm.runInNewContext(fs.readFileSync(__dirname + '/app/app.js', 'utf8'), {
    document: {
        getElementById: id => nodes[id], addEventListener: (name, fn) => { listeners[name] = fn; },
        createElement: () => ({ focus() { this.focused = true; } })
    },
    window: { PalmSystem: { launchParams: '{}' }, close() {}, addEventListener() {} },
    localStorage: { getItem: () => 'com.webos.app.hdmi4', setItem() {} },
    PalmServiceBridge: Bridge, Date, setInterval() { return 1; }, clearInterval() {}
});
const list = bridges.find(b => b.uri.endsWith('/getAllInputStatus'));
list.onservicecallback(JSON.stringify({ returnValue: true, devices: [
    { appId: 'com.webos.app.hdmi2', port: 2, label: '<script>PS5</script>' },
    { appId: 'com.webos.app.hdmi4', port: 4, label: 'Xbox' }
] }));
const buttons = nodes['input-options'].children;
assert.equal(nodes.inputs.hidden, false);
assert.equal(buttons.length, 2);
assert.equal(buttons[1].focused, true);
assert.match(buttons[0].textContent, /<script>PS5<\/script>/, 'Název vstupu se vykresluje jako text.');
buttons[1].onclick();
assert.equal(bridges.at(-1).payload.id, 'com.webos.app.hdmi4');
assert.equal(buttons[0].disabled, true);
bridges.at(-1).onservicecallback(JSON.stringify({ returnValue: false, errorText: 'denied' }));
assert.equal(nodes['input-status'].textContent, 'denied');
assert.equal(buttons[0].disabled, false);
listeners.webOSRelaunch({ detail: { resumeOverlay: true } });
assert.equal(nodes.inputs.hidden, true);
listeners.webOSRelaunch({ detail: {} });
bridges.at(-1).onservicecallback(JSON.stringify({ returnValue: true, devices: {} }));
assert.match(nodes['input-status'].textContent, /neposkytla/);
listeners.webOSRelaunch({ detail: {} });
bridges.at(-1).onservicecallback('null');
assert.match(nodes['input-status'].textContent, /neposkytla/);
listeners.webOSRelaunch({ detail: {} });
nodes['input-options'].children = [];
bridges.at(-1).onservicecallback(JSON.stringify({ returnValue: true, devices: [
    { appId: 'com.webos.app.hdmi1' }, { appId: 'com.webos.app.hdmi3', port: 99, label: 'Počítač' },
    { appId: 'com.webos.app.browser', port: 2 }
] }));
assert.deepEqual(nodes['input-options'].children.map(b => b.textContent), ['HDMI 1', 'HDMI 3 · Počítač']);
console.log('OK: výběr skutečného HDMI, návrat overlay ze služby, validace, timeout, uvolnění aktivity a UI.');
