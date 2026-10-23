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
    assert.match(e.ids['self-ready-status'].textContent, /あなたは準備前です/);
    assert.equal(e.ids['start-action'].textContent, '');
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
