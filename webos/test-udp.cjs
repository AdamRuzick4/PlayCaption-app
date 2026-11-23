/* SPDX-License-Identifier: AGPL-3.0-or-later */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const dgram = require('node:dgram');
const { once } = require('node:events');

// Skutečné UDP sockety, pouze testovací porty a cíle jsou přesměrované na loopback.
(async () => {
    const discovery = dgram.createSocket('udp4');
    const sender = dgram.createSocket('udp4');
    const sockets = [], timers = new Set();
    const handlers = {};
    const nativeSend = dgram.Socket.prototype.send;
    let pending;
    const watchdog = setTimeout(() => { console.error('UDP test timeout'); process.exit(1); }, 5000);
    function Service() { this.register = (name, request, cancel) => { handlers[name] = { request, cancel }; }; }
    try {
        discovery.bind(0, '127.0.0.1');
        await once(discovery, 'listening');
        vm.runInNewContext(fs.readFileSync(__dirname + '/service/service.js', 'utf8'), {
            require(name) {
                if (name === 'webos-service') return Service;
                if (name === 'os') return {
                    hostname: () => 'LG-test', networkInterfaces: () => ({
                        test: [{ internal: false, family: 'IPv4', address: '127.0.0.1', netmask: '255.0.0.0', mac: '00:11:22:33:44:55' }]
                    })
                };
                if (name === 'dgram') return { Socket: dgram.Socket, createSocket() {
                    const socket = dgram.createSocket('udp4');
                    const bind = socket.bind.bind(socket), send = socket.send.bind(socket);
                    socket.bind = (port, host, callback) => {
                        assert.equal(port, 41989);
                        bind(0, '127.0.0.1', callback);
                    };
                    socket.send = (data, offset, length, port, host, callback) => {
                        assert.equal(offset, 0);
                        assert.equal(length, data.length);
                        if (port === 41988) {
                            assert.equal(host, '127.255.255.255');
                            send(data, offset, length, discovery.address().port, '127.0.0.1', callback);
                        } else { assert.equal(host, '127.0.0.1'); send(data, offset, length, port, host, callback); }
                    };
                    sockets.push(socket);
                    return socket;
                } };
                return require(name.startsWith('./') ? './service/' + name.slice(2) : name);
            },
            Buffer, console,
            setInterval(callback, ms) { const timer = setInterval(callback, ms); timers.add(timer); return timer; },
            clearInterval(timer) { clearInterval(timer); timers.delete(timer); }
        });
        assert.equal(dgram.Socket.prototype.send, nativeSend, "LG nesmí změnit odesílání ostatních UDP socketů");
        const ready = new Promise(resolve => {
            handlers.listen.request({ isSubscription: true, uniqueToken: 'udp-test', respond(result) {
                assert.equal(result.returnValue, true, result.errorText);
                if (result.ready) resolve();
                if (result.packet && pending) { pending(result); pending = null; }
            } });
        });
        const announced = once(discovery, 'message');
        await ready;
        const [announcement] = await announced;
        assert.match(announcement.toString(), /^PLAYCAPTION_TV\tlg-[0-9a-f]{12}\tLG PlayCaption · [0-9A-F]{12}\t0$/);
        const direct = once(sender, 'message');
        const nonce = 'd'.repeat(32);
        sender.send(Buffer.from('PLAYCAPTION_FIND\t'+nonce), sockets[0].address().port, '127.0.0.1');
        assert.equal((await direct)[0].toString(), announcement.toString()+'\t'+nonce);
        const delivered = new Promise(resolve => { pending = resolve; });
        sender.send(Buffer.from('PCT1\t42\t6000\tPrvní řádek\\nDruhý řádek\tNápověda'), sockets[0].address().port, '127.0.0.1');
        const result = await delivered;
        assert.equal(result.packet.text, 'První řádek\nDruhý řádek');
        assert.equal(result.packet.hint, 'Nápověda');
        assert.equal(result.source, '127.0.0.1');
        for (const [text, kind] of [
            ['PCV1\t42\t2\t0.55\t0.45\t0.95\t0.8\t0\tPrvní volba\\nDruhá volba', 'choices'],
            ['PCO1\t1\t0.1\t0.72\t0.9\t0.97', 'ocr']
        ]) {
            const deliveredFrame = new Promise(resolve => { pending = resolve; });
            sender.send(Buffer.from(text), sockets[0].address().port, '127.0.0.1');
            assert.equal((await deliveredFrame).packet.kind, kind);
        }
        const id = 'a'.repeat(32) + '.1.1.0';
        const capability = once(sender, 'message');
        sender.send(Buffer.from('PCQ1\t'+id), sockets[0].address().port, '127.0.0.1');
        assert.equal((await capability)[0].toString(), 'PCA1\t'+id+'\tready');
        const story = new Promise(resolve=>{pending=resolve});
        sender.send(Buffer.from('PCS1\t'+id+'\t0\tPříběh\t0.5\t0.9\t0.8\t1\t'),sockets[0].address().port,'127.0.0.1');
        assert.equal((await story).packet.kind,'story');
        let denied;
        handlers.storyAck.request({payload:{id:'wrong',state:'done'},respond:r=>{denied=r}});
        assert.equal(denied.returnValue,false);
        const acknowledged=once(sender,'message');
        await new Promise(resolve=>handlers.storyAck.request({payload:{id,state:'done'},respond:r=>{assert.equal(r.returnValue,true);resolve()}}));
        assert.equal((await acknowledged)[0].toString(),'PCA1\t'+id+'\tdone');
        const closed = once(sockets[0], 'close');
        handlers.listen.cancel({ uniqueToken: 'udp-test' });
        await closed;
        assert.equal(timers.size, 0);
        console.log('OK: skutečné UDP discovery, příjem českého víceřádkového paketu a zavření socketu po odhlášení.');
    } finally {
        clearTimeout(watchdog);
        timers.forEach(clearInterval);
        for (const socket of [...sockets, sender, discovery]) {
            try { socket.close(); } catch (error) {
                if (error.code !== 'ERR_SOCKET_DGRAM_NOT_RUNNING') throw error;
            }
        }
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
