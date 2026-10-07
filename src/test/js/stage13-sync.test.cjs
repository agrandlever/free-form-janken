'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { environment, selecting } = require('./sync-test-support.cjs');

for (const final of [false, true]) {
    test((final ? '正常終了10秒' : '勝者なし5秒') + '：カウント0では待機しサーバー変更後の2秒pollで同じIDへ移動', async () => {
        const e = environment('round-result');
        e.page.dataset.matchFinished = String(final);
        e.load('round-result.js'); e.load('status-polling.js'); e.start();
        const duration = final ? 10000 : 5000;
        const result = selecting({ matchState: 'ROUND_RESULT', displayMatchState: 'ROUND_RESULT',
            serverTime: '2026-10-07T00:00:00Z', displayTransitionAt: final ? '2026-10-07T00:00:10Z' : '2026-10-07T00:00:05Z' });
        await e.reply(result);
        assert.equal(e.timeouts[0].delay, 2000);
        e.setTime(duration); e.intervals[0].fn();
        assert.match(e.ids['transition-count'].textContent, / 0 秒$/); assert.deepEqual(e.moves, []);
        // 期限到達後もサーバーがROUND_RESULTなら、次回確認を続ける。
        e.timeouts.shift().fn(); await e.reply({ ...result, serverTime: result.displayTransitionAt });
        assert.deepEqual(e.moves, []); assert.equal(e.timeouts[0].delay, 2000);
        e.timeouts.shift().fn();
        await e.reply(final ? selecting({ userState: 'ROOM_WAITING', currentMatchId: null, matchState: null,
            displayMatchState: 'MATCH_RESULT', displayRoundNumber: null, displayTransitionAt: null })
            : selecting({ displayRoundNumber: 2 }));
        assert.deepEqual(e.moves, [(final ? '/match-result' : '/play') + '?matchId=M']);
        assert.ok(e.fetches.every(f => f.url === '/api/status?matchId=M'));
    });
}
