'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { environment, selecting } = require('./sync-test-support.cjs');
const ended = (extra = {}) => selecting({ userState: 'ROOM_WAITING', currentMatchId: null, matchState: null,
    displayMatchState: 'MATCH_RESULT', displayRoundNumber: null, displayTransitionAt: null,
    selfHandConfirmed: null, selfSelectedHand: null, ...extra });

test('match-resultも同じIDで2秒pollを続ける', async () => {
    const e=environment('match-result'); e.load('status-polling.js'); e.start();
    await e.reply(ended());assert.equal(e.timeouts[0].delay,2000);assert.equal(e.fetches[0].url,'/api/status?matchId=M');
    e.timeouts.shift().fn();await e.reply(ended());assert.equal(e.fetches[1].url,'/api/status?matchId=M');assert.deepEqual(e.moves,[]);
});
for (const state of ['ROOM_WAITING','READY']) {
    test(state+'の結果はM2開始・終了とlatest更新後もM1を維持する', async () => {
        const e=environment('match-result');e.load('status-polling.js');e.start();
        await e.reply(ended({userState:state,currentMatchId:'M2',matchState:'SELECTING_HAND',lastCompletedMatchId:'M'}));
        assert.deepEqual(e.moves,[]);e.timeouts.shift().fn();
        await e.reply(ended({userState:state,lastCompletedMatchId:'M2'}));assert.deepEqual(e.moves,[]);
        assert.equal(e.page.dataset.matchId,'M');assert.ok(e.fetches.every(f=>f.url==='/api/status?matchId=M'));
    });
}
for (const state of ['SELECTING_HAND','ROUND_RESULT']) {
    test('本人④化ならM1結果から新対戦M2の'+state+'を優先する', async () => {
        const e=environment('match-result');e.load('status-polling.js');e.start();
        await e.reply(selecting({currentMatchId:'M2',matchState:state,displayMatchState:'MATCH_RESULT'}));
        assert.deepEqual(e.moves,[(state==='SELECTING_HAND'?'/play':'/round-result')+'?matchId=M2']);
    });
}
for (const kind of ['play','round-result']) {
    for (const endType of ['NORMAL','ABORTED']) {
        test(kind+'の'+endType+'終了後は最新M2でなく表示中M1へ移る', async () => {
            const e=environment(kind);e.load('status-polling.js');e.start();
            await e.reply(ended({lastCompletedMatchId:'M2'}));assert.deepEqual(e.moves,['/match-result?matchId=M']);
        });
    }
}
test('結果画面のアクセス権喪失を維持より優先する', async () => {
    for(const [data,code,url] of [[{},401,'/'],[ended({userState:'ROOM_NONE'}),200,'/rooms'],
        [ended({currentRoomId:'Other'}),200,'/room'],[ended({displayMatchId:null,displayMatchState:null}),200,'/room']]) {
        const e=environment('match-result');e.load('status-polling.js');e.start();await e.reply(data,code);assert.deepEqual(e.moves,[url]);
    }
});
test('相性表は初期非表示から開閉し文言・ariaを更新、通信なし', () => {
    const e=environment('match-result');const table=e.ids['original-hand-affinities'];const button=e.ids['toggle-affinities'];table.hidden=true;
    button.textContent='オリジナル手の相性を表示';e.load('match-result.js');assert.equal(table.hidden,true);
    button.emit('click');assert.equal(table.hidden,false);assert.equal(button.textContent,'オリジナル手の相性を非表示');assert.equal(button.attributes['aria-expanded'],'true');
    button.emit('click');assert.equal(table.hidden,true);assert.equal(button.textContent,'オリジナル手の相性を表示');assert.equal(button.attributes['aria-expanded'],'false');
    assert.equal(e.fetches.length,0);assert.deepEqual(e.moves,[]);assert.equal(e.page.dataset.matchId,'M');
});
