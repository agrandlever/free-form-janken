'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { environment, selecting } = require('./sync-test-support.cjs');

for (const kind of ['room', 'play', 'round-result', 'match-result']) {
    test(kind + 'はtimeout後の正常①statusでroomsへ移動しログアウトと区別する', async () => {
        const e = environment(kind); e.load('status-polling.js'); e.start();
        await e.reply(selecting({ userState: 'ROOM_NONE', currentRoomId: null, currentMatchId: null,
            matchState: null, displayMatchId: null, displayMatchState: null, selfHandConfirmed: null, selfSelectedHand: null }));
        assert.deepEqual(e.moves, ['/rooms']);
    });
}
for (const state of ['ROOM_WAITING', 'READY']) {
    test(state + 'の観戦は2秒pollが継続し本人確定UIを作らない', async () => {
        const e = environment('play'); e.page.dataset.matchParticipant = 'false';
        e.load('play.js'); e.load('status-polling.js'); e.start();
        for (let second = 0; second < 64; second += 2) {
            await e.reply(selecting({ userState: state, selfHandConfirmed: null, selfSelectedHand: null }));
            assert.deepEqual(e.moves, []); assert.equal(e.timeouts.at(-1).delay, 2000);
            assert.equal(e.ids['self-hand-status'].textContent, '');
            e.setTime((second + 2) * 1000); e.timeouts.shift().fn();
        }
        assert.equal(e.fetches.length, 33);
    });
}
for (const fails of [false, true]) {
    test('TC-010 コピー' + (fails ? '失敗は案内を表示' : '成功は最新roomNameだけをコピー'), async () => {
        const e = environment('room'); let click; const copied = [];
        e.ids['copy-room-name'].addEventListener = (type, fn) => { if (type === 'click') click = fn; };
        const context = vm.createContext({
            document: { querySelector: () => e.page, getElementById: id => e.ids[id], addEventListener() {} },
            navigator: { clipboard: { async writeText(text) { if (fails) throw new Error('denied'); copied.push(text); } } }
        });
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/room.js'), 'utf8'), context);
        e.ids['display-room-name'].textContent = '共有する部屋'; await click();
        assert.deepEqual(copied, fails ? [] : ['共有する部屋']);
        assert.equal(e.ids['copy-message'].textContent, fails ? 'コピーできませんでした。ルーム名を選択してコピーしてください。' : 'ルーム名をコピーしました');
        assert.ok(!e.ids['copy-message'].textContent.includes('R'));
    });
}
for (const kind of ['rooms', 'room']) {
    test(kind + 'のインラインフォームは閉じても入力値を保持し再初期化でイベント重複しない', () => {
        const e = environment(kind); const open = e.ids['open-original-hand'];
        open.focus = () => { open.focused = true; };
        e.ids['close-original-hand'] = { listeners: {}, addEventListener(n, f) { this.listeners[n] = f; } };
        e.ids['original-hand-name'] = { value: '入力途中', focus() { this.focused = true; } };
        e.ids['original-hand-panel'].hidden = true; e.load('original-hand.js'); e.load('original-hand.js');
        assert.equal(open.listeners.click.length, 1); open.emit('click');
        assert.equal(e.ids['original-hand-panel'].hidden, false); assert.equal(e.ids['original-hand-name'].focused, true);
        e.ids['close-original-hand'].listeners.click(); assert.equal(e.ids['original-hand-panel'].hidden, true);
        assert.equal(open.focused, true); open.emit('click'); assert.equal(e.ids['original-hand-name'].value, '入力途中');
        assert.deepEqual(e.moves, []); assert.equal(e.fetches.length, 0);
    });
}