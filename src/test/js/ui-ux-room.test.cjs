'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { environment } = require('./sync-test-support.cjs');

test('準備人数・本人の案内を状態同期し、ホスト移譲後も開始条件を維持する', () => {
    const e = environment('room');
    e.load('room.js');
    const status = { userState: 'ROOM_WAITING', currentRoomId: 'R', roomState: 'WAITING', room: {
        id: 'R', name: 'テスト', hostUserId: 'A', targetWins: 3, preventConsecutiveSameOriginalHand: false,
        members: [{ username: '自分', isHost: true, userState: 'ROOM_WAITING' },
            { username: '友達', isHost: false, userState: 'READY' }]
    }};
    e.emit(status);
    assert.equal(e.ids['room-status'].textContent, '参加者 2人・準備完了 1人');
    assert.match(e.ids['room-members'].textContent, /自分（ホスト）（準備前）/);
    assert.match(e.ids['self-ready-status'].textContent, /自分の手を1つ作りましょう/);
    assert.equal(e.ids['start-action'].textContent, '対戦開始');
    assert.equal(e.ids['start-action'].querySelector('button').disabled, true);
    e.emit({ ...status, userState: 'READY', room: { ...status.room,
        members: status.room.members.map(m => ({ ...m, userState: 'READY' })) } });
    assert.equal(e.ids['room-status'].textContent, '参加者 2人・準備完了 2人');
    assert.match(e.ids['self-ready-status'].textContent, /あなたは準備完了です/);
    assert.equal(e.ids['start-action'].textContent, '対戦開始');
    e.emit({ ...status, room: { ...status.room, hostUserId: 'B' } });
    assert.equal(e.ids['start-action'].textContent, '');
    assert.equal(e.ids['rule-editor'].hidden, true);
    // 別ルームからの遅延応答で、表示中の人数を上書きしない。
    e.emit({ ...status, currentRoomId: 'OTHER', room: { ...status.room, members: [] } });
    assert.equal(e.ids['room-status'].textContent, '参加者 2人・準備完了 1人');
});

function roomStatus(extra = {}) {
    return { userState: 'ROOM_WAITING', currentRoomId: 'R', roomState: 'WAITING', room: {
        id: 'R', name: 'テスト', hostUserId: 'A', targetWins: 3, preventConsecutiveSameOriginalHand: false,
        members: [{ username: '自分', userState: 'ROOM_WAITING', isHost: true }]
    }, ...extra };
}
function renderedRoom(created, roomId = 'R') {
    const server = environment('room');
    server.page.dataset.roomId = roomId;
    server.ids['create-original-hand'].hidden = created;
    server.ids['open-original-hand'].textContent = created ? 'オリジナル手を編集' : 'オリジナル手を作る';
    server.ids['original-hand-status'].textContent = created ? '保存した手：にじ' : 'オリジナル手が作成されていません';
    server.ids['saved-hand-summary'].hidden = !created;
    server.ids['ready-saved-hand'].hidden = !created;
    server.ids['ready-saved-hand'].textContent = created ? '保存した手：にじ' : '';
    server.ids['saved-hand-summary'].textContent = created ? '対グー：負け・対チョキ：勝ち・対パー：引き分け・対オリジナル：負け' : '';
    return {
        querySelector: selector => selector.startsWith('main') ? server.page
            : { querySelector: () => server.ids['original-hand-panel'] },
        getElementById: id => server.ids[id]
    };
}

test('別タブでの作成・削除を既存HTMLから同期し、編集中の入力と開閉状態を保持する', async () => {
    const e = environment('room'); e.load('room.js');
    e.controls[0].value = '未保存の入力'; e.ids['original-hand-panel'].hidden = false;
    e.emit(roomStatus());
    assert.equal(e.ids['ready-action'].hidden, true);
    assert.equal(e.ids['create-original-hand'].hidden, false);
    await e.replyHtml(renderedRoom(true));
    assert.equal(e.ids['ready-action'].hidden, false);
    assert.equal(e.ids['create-original-hand'].hidden, true);
    assert.equal(e.ids['self-ready-status'].textContent, '手を確認したら、準備完了を押しましょう。');
    assert.equal(e.ids['original-hand-status'].textContent, '保存した手：にじ');
    assert.equal(e.ids['ready-saved-hand'].textContent, '保存した手：にじ');
    assert.equal(e.ids['saved-hand-summary'].hidden, false);
    assert.match(e.ids['saved-hand-summary'].textContent, /対グー：負け/);
    assert.equal(e.controls[0].value, '未保存の入力');
    assert.equal(e.ids['original-hand-panel'].hidden, false);
    e.emit(roomStatus()); await e.replyHtml(renderedRoom(false));
    assert.equal(e.ids['ready-action'].hidden, true);
    assert.equal(e.ids['create-original-hand'].hidden, false);
    assert.equal(e.controls[0].value, '未保存の入力');
    assert.equal(e.ids['saved-hand-summary'].hidden, true);
});

test('別タブの再保存で要約だけ更新し、未保存の入力を要約に混ぜない', async () => {
    const e = environment('room'); e.load('room.js');
    e.controls[0].value = '未保存の名前';
    e.emit(roomStatus()); await e.replyHtml(renderedRoom(true));
    const updated = renderedRoom(true);
    updated.getElementById('saved-hand-summary').textContent = '対グー：引き分け・対チョキ：負け・対パー：勝ち・対オリジナル：引き分け';
    e.emit(roomStatus()); await e.replyHtml(updated);
    assert.match(e.ids['saved-hand-summary'].textContent, /対グー：引き分け/);
    assert.equal(e.controls[0].value, '未保存の名前');
    assert.ok(!e.ids['saved-hand-summary'].textContent.includes('未保存の名前'));
});

test('HTML取得が重ならず、READY化・別ルームの応答で主操作を戻さない', async () => {
    const e = environment('room'); e.load('room.js');
    e.emit(roomStatus()); e.emit(roomStatus());
    assert.equal(e.fetches.length, 1);
    e.emit(roomStatus({ userState: 'READY' }));
    await e.replyHtml(renderedRoom(false));
    assert.equal(e.ids['ready-action'].hidden, false);
    assert.equal(e.ids['ready-action'].textContent, '準備取消');
    assert.equal(e.ids['create-original-hand'].hidden, true);
    e.emit(roomStatus()); await e.replyHtml(renderedRoom(false, 'OTHER'));
    assert.equal(e.ids['create-original-hand'].hidden, true);
    e.emit(roomStatus()); await e.replyHtml(null, 500);
    assert.equal(e.ids['create-original-hand'].hidden, true);
});

test('READYの待機案内は人数・ホスト・対戦中の既存状態に従う', () => {
    const e = environment('room'); e.load('room.js');
    const status = roomStatus({ userState: 'READY' });
    e.emit(status);
    assert.equal(e.ids['ready-waiting-message'].textContent, '参加者が2人必要です。ルーム名を共有して相手を招待してください。');
    e.emit({ ...status, room: { ...status.room, members: [{ userState: 'READY' }, { userState: 'ROOM_WAITING' }] } });
    assert.equal(e.ids['ready-waiting-message'].textContent, '相手の準備完了を待っています。');
    const room = { ...status.room, members: [{ userState: 'READY' }, { userState: 'READY' }] };
    e.emit({ ...status, room });
    assert.equal(e.ids['ready-waiting-message'].textContent, '対戦を開始できます。');
    e.emit({ ...status, room: { ...room, hostUserId: 'B' } });
    assert.equal(e.ids['ready-waiting-message'].textContent, 'ホストの対戦開始を待っています。');
    e.emit({ ...status, roomState: 'PLAYING' });
    assert.equal(e.ids['ready-waiting-message'].textContent, '現在の対戦が終わるのを待っています。');
});

test('ルールの開閉は入力を保持し、権限喪失で閉じて送信を無効化する', () => {
    const e = environment('room'); e.load('room.js');
    const status = roomStatus(); e.emit(status);
    const editor = e.ids['rule-editor']; editor.open = true;
    const wins = editor.querySelector('[name="targetWins"]');
    wins.value = '入力中'; editor.emit('input'); editor.open = false; editor.open = true;
    e.emit({ ...status, room: { ...status.room, targetWins: 7, preventConsecutiveSameOriginalHand: true } });
    assert.equal(wins.value, '入力中'); assert.equal(editor.open, true);
    assert.equal(e.ids['display-target-wins'].textContent, '7');
    assert.equal(e.ids['display-prevent-consecutive'].textContent, '不可');
    e.emit({ ...status, room: { ...status.room, hostUserId: 'B' } });
    assert.equal(editor.open, false); assert.equal(editor.hidden, true);
    assert.ok(editor.querySelectorAll('input,button').every(c => c.disabled));
});

test('上部の手作成導線は共通フォームを展開し名前にフォーカス・スクロールする', () => {
    const e = environment('room');
    for (const id of ['close-original-hand', 'original-hand-name']) e.ids[id] = e.ids['room-status'].owner.createElement('input');
    e.ids['original-hand-panel'].hidden = true;
    e.load('original-hand.js'); e.load('original-hand.js');
    e.ids['create-original-hand'].click();
    assert.equal(e.ids['original-hand-panel'].hidden, false);
    assert.equal(e.ids['original-hand-name'].focused, true);
    assert.equal(e.ids['original-hand-name'].scrollOptions.block, 'center');
    assert.equal(e.ids['original-hand-name'].scrollOptions.behavior, 'instant');
    assert.equal(e.ids['create-original-hand'].attributes['aria-expanded'], 'true');
    assert.equal(e.ids['create-original-hand'].listeners.click.length, 1);
    e.ids['close-original-hand'].click();
    assert.equal(e.ids['create-original-hand'].attributes['aria-expanded'], 'false');
});

test('ルールのエラー再表示は最初の定期更新でも入力を上書きしない', () => {
    const e = environment('room');
    const editor = e.ids['rule-editor'];
    const query = editor.querySelector.bind(editor);
    editor.querySelector = selector => selector === '[id$="errors"] li' ? {} : query(selector);
    e.load('room.js'); e.emit(roomStatus());
    const wins = editor.querySelector('[name="targetWins"]'); wins.value = 'abc';
    e.emit(roomStatus());
    assert.equal(wins.value, 'abc');
    assert.equal(e.ids['display-target-wins'].textContent, '3');
});

test('同じ状態の準備ボタンは定期更新で作り直さずフォーカスを保持する', () => {
    const e = environment('room'); e.load('room.js'); e.emit(roomStatus());
    const form = e.ids['ready-action'];
    const button = form.querySelector('button'); button.focus();
    e.emit(roomStatus());
    assert.equal(e.ids['ready-action'], form);
    assert.equal(e.ids['ready-action'].querySelector('button'), button);
    assert.equal(button.focused, true);
});

test('エラー再表示は最初の入力へ、入力がない業務エラーは概要へフォーカスする', () => {
    const fs = require('node:fs'); const vm = require('node:vm'); const path = require('node:path');
    for (const hasInput of [true, false]) {
        const invalid = { focus() { this.focused = true; }, scrollIntoView() {} };
        const link = { getAttribute: () => '#vsRock-WIN', addEventListener: (_, fn) => { link.click = fn; } };
        const summary = { focus() { this.focused = true; }, scrollIntoView() {}, querySelectorAll: () => [link] };
        const document = { querySelector: selector => selector === '.errors' ? summary : hasInput ? invalid : null,
            getElementById: () => invalid };
        vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/form-feedback.js'), 'utf8'), { document });
        assert.equal(hasInput ? invalid.focused : summary.focused, true);
        let prevented = false; link.click({ preventDefault() { prevented = true; } });
        assert.equal(invalid.focused, true); assert.equal(prevented, true);
    }
});

test('全体条件の初期フォーカスは概要で、リンクは共通領域へフォーカス・スクロールする', () => {
    const fs = require('node:fs'); const vm = require('node:vm'); const path = require('node:path');
    const group = { focus(options) { this.focused = options; }, scrollIntoView(options) { this.scrolled = options; } };
    const link = { getAttribute: () => '#affinity-fields', addEventListener: (_, fn) => { link.click = fn; } };
    const summary = { focus() { this.focused = true; }, scrollIntoView() {}, querySelectorAll: () => [link] };
    const document = { querySelector: selector => selector === '.errors' ? summary : null,
        getElementById: id => id === 'affinity-fields' ? group : null };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/form-feedback.js'), 'utf8'), { document });
    assert.equal(summary.focused, true);
    link.click({ preventDefault() {} });
    assert.equal(group.focused.preventScroll, true);
    assert.equal(group.scrolled.block, 'center');
});

test('参加者の役割と各状態を別クラスにし、更新後も文字を保持する', () => {
    const e = environment('room'); e.load('room.js');
    const status = roomStatus();
    status.room.members = [
        { username: 'A', isHost: true, userState: 'ROOM_WAITING' },
        { username: 'B', userState: 'READY' }, { username: 'C', userState: 'PLAYING' }
    ];
    for (let i = 0; i < 2; i++) {
        e.emit(status);
        const rows = e.ids['room-members'].children;
        assert.equal(rows[0].children[1].className, 'member-role');
        assert.equal(rows[0].children[1].textContent, '（ホスト）');
        assert.equal(rows[0].children[2].className, 'member-state member-waiting');
        assert.equal(rows[1].children[1].className, 'member-state member-ready');
        assert.equal(rows[2].children[1].className, 'member-state member-playing');
    }
});

test('ホスト移譲で生成する先取勝数にも補足と入力の関連付けがある', () => {
    const e = environment('room');
    e.ids['rule-editor'].replaceChildren();
    e.load('room.js'); e.emit(roomStatus());
    const editor = e.ids['rule-editor'];
    const wins = editor.querySelector('[name="targetWins"]');
    assert.equal(wins.id, 'targetWins');
    assert.equal(wins.attributes['aria-describedby'], 'targetWins-hint');
    const hint = editor.querySelectorAll('p').find(p => p.id === 'targetWins-hint');
    assert.equal(hint.textContent, '1～99の整数');
    assert.equal(wins.value, 3);
});
