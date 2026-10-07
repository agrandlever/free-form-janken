'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { environment, selecting } = require('./sync-test-support.cjs');
test('履歴は表示だけを切り替え、文言・ariaも更新する', () => {
    const e=environment('play');e.load('play.js');
    e.ids['toggle-round-history'].emit('click');
    assert.equal(e.ids['round-history'].hidden,false);
    assert.equal(e.ids['toggle-round-history'].textContent,'ラウンド履歴を非表示');
    assert.equal(e.ids['toggle-round-history'].attributes['aria-expanded'],'true');
    e.ids['toggle-round-history'].emit('click');
    assert.equal(e.ids['round-history'].hidden,true);
    assert.equal(e.ids['toggle-round-history'].textContent,'ラウンド履歴を表示');
    assert.equal(e.fetches.length,0);
});
test('別タブの確定で全ボタン無効化し遅延falseを無視する', () => {
    const e=environment('play');e.load('play.js');
    e.emit(selecting({selfHandConfirmed:true,selfSelectedHand:{handName:'<特殊な手>'}}));
    assert.ok(e.page.buttons.every(b=>b.disabled));
    assert.equal(e.ids['self-hand-status'].textContent,'確定済みの手：<特殊な手>。他の参加者の確定を待っています。');
    e.emit(selecting());assert.ok(e.page.buttons.every(b=>b.disabled));
    assert.equal(e.moves.length,0);
});
test('初期HTMLで確定済みなら古いfalseを採用しない', () => {
    const e=environment('play',true);e.load('play.js');e.emit(selecting());
    assert.ok(e.page.buttons.every(b=>b.disabled));
});
test('別対戦・別Roundの本人手情報は反映しない', () => {
    const e=environment('play');e.load('play.js');
    for (const extra of [{displayMatchId:'OTHER'},{displayRoundNumber:2},{displayMatchState:'ROUND_RESULT'}])
        e.emit(selecting({...extra,selfHandConfirmed:true,selfSelectedHand:{handName:'秘密'}}));
    assert.ok(e.page.buttons.every(b=>!b.disabled));
});
test('pollは2秒間隔・対象ID付き・重ならない', async () => {
    const e=environment('play');e.load('status-polling.js');e.start();
    assert.equal(e.fetches.length,1);assert.equal(e.timeouts.length,0);
    assert.equal(e.fetches[0].url,'/api/status?matchId=M');
    e.setTime(50);await e.reply(selecting());
    assert.equal(e.timeouts[0].delay,1950);
    e.timeouts.shift().fn();assert.equal(e.fetches.length,2);assert.equal(e.timeouts.length,0);
    await e.reply(selecting());assert.equal(e.moves.length,0);
});
test('同じ対戦のplayからround-resultへ移動する', async () => {
    const e=environment('play');e.load('status-polling.js');e.start();
    await e.reply(selecting({matchState:'ROUND_RESULT',displayMatchState:'ROUND_RESULT'}));
    assert.deepEqual(e.moves,['/round-result?matchId=M']);
});
test('将来サーバーがSELECTINGになった時だけ同じIDのplayへ移動する', async () => {
    const e=environment('round-result');e.load('status-polling.js');e.start();await e.reply(selecting());
    assert.deepEqual(e.moves,['/play?matchId=M']);
});
test('同じRoundではreloadせず新RoundだけGET playを取得する', async () => {
    const e=environment('play');e.load('status-polling.js');e.start();await e.reply(selecting());
    assert.equal(e.moves.length,0);e.timeouts.shift().fn();
    await e.reply(selecting({displayRoundNumber:2}));assert.deepEqual(e.moves,['/play?matchId=M']);
});
test('無効display情報で対象を別対戦へ置き換えない', async () => {
    const e=environment('play');e.load('status-polling.js');e.start();
    await e.reply(selecting({displayMatchId:'OTHER',displayMatchState:'ROUND_RESULT'}));
    assert.equal(e.moves.length,0);
});
test('roomsの別タブ入室・準備・対戦開始を正規画面へ同期する', async () => {
    for(const state of ['ROOM_WAITING','READY','PLAYING']) {
        const e=environment('rooms');e.load('status-polling.js');e.start();
        await e.reply(selecting({userState:state}));
        assert.deepEqual(e.moves,[state==='PLAYING'?'/play?matchId=M':'/room']);
    }
});
test('roomの②・③は対戦中でも留まり④だけ移動する', async () => {
    for(const state of ['ROOM_WAITING','READY','PLAYING']) {
        const e=environment('room');e.load('status-polling.js');e.start();
        await e.reply(selecting({userState:state}));
        assert.deepEqual(e.moves,state==='PLAYING'?['/play?matchId=M']:[]);
    }
});
test('未ログイン・①・別Roomはアクセス制御を優先する', async () => {
    for(const [code,status,url] of [[401,{},'/'],[200,selecting({userState:'ROOM_NONE'}),'/rooms'],
        [200,selecting({userState:'READY',currentRoomId:'OTHER'}),'/room']]) {
        const e=environment('play');e.load('status-polling.js');e.start();await e.reply(status,code);
        assert.deepEqual(e.moves,[url]);
    }
});
test('カウントはserverTime基準で5秒・10秒・最終表示、0秒で移動しない', async () => {
    for (const [seconds,final] of [[5,false],[10,false],[10,true]]) {
        const e=environment('round-result');e.page.dataset.matchFinished=String(final);
        e.load('round-result.js');e.load('status-polling.js');e.start();
        const status=selecting({matchState:'ROUND_RESULT',displayMatchState:'ROUND_RESULT',
            serverTime:'2000-01-01T00:00:00Z',displayTransitionAt:'2000-01-01T00:00:'+String(seconds).padStart(2,'0')+'Z'});
        await e.reply(status);
        const label=final?'対戦結果まで ':'次のラウンドまで ';
        assert.equal(e.ids['transition-count'].textContent,label+seconds+' 秒');
        e.setTime(1100);e.intervals[0].fn();assert.equal(e.ids['transition-count'].textContent,label+(seconds-1)+' 秒');
        e.setTime(11000);e.intervals[0].fn();assert.equal(e.ids['transition-count'].textContent,label+'0 秒');
        assert.equal(e.moves.length,0);assert.equal(e.timeouts.length,1);
        e.timeouts.shift().fn();await e.reply({...status,serverTime:'2000-01-01T00:00:20Z'});
        assert.equal(e.moves.length,0);assert.equal(e.ids['transition-count'].textContent,label+'0 秒');
    }
});
test('通信失敗後もpollを続けHTMLや失敗JSONを状態として採用しない', async () => {
    const e=environment('play');e.load('status-polling.js');e.start();await e.reply({code:'ERROR'},500);
    assert.equal(e.moves.length,0);assert.equal(e.timeouts.length,1);
});
test('ルームの参加者・ホスト・ルール・準備・開始条件を部分更新し編集値を保持する', () => {
    const e=environment('room');e.load('room.js');
    const status={userState:'ROOM_WAITING',currentRoomId:'R',roomState:'WAITING',room:{
        id:'R',name:'更新後',hostUserId:'A',targetWins:5,preventConsecutiveSameOriginalHand:true,
        members:[{userId:'A',username:'Alice',userState:'READY',isHost:true},
            {userId:'B',username:'Bob',userState:'READY',isHost:false}]}};
    e.emit(status);
    assert.equal(e.ids['display-room-name'].textContent,'更新後');
    assert.ok(e.ids['room-members'].textContent.includes('Alice（ホスト）（準備完了）'));
    assert.equal(e.ids['start-action'].textContent,'対戦開始');
    assert.equal(e.ids['ready-action'].textContent,'準備完了');
    const form=e.ids['rule-editor'].querySelector('form');
    const wins=form.querySelector('[name="targetWins"]');assert.equal(wins.value,5);
    wins.value='入力途中';e.ids['rule-editor'].emit('input');
    e.emit({...status,room:{...status.room,targetWins:7}});
    assert.equal(wins.value,'入力途中');assert.equal(e.ids['display-target-wins'].textContent,'7');
    e.emit({...status,userState:'READY',roomState:'PLAYING',room:{...status.room,hostUserId:'B'}});
    assert.equal(e.ids['ready-action'].textContent,'準備取消');
    assert.equal(e.ids['start-action'].textContent,'');assert.equal(e.ids['match-running'].hidden,false);
    assert.equal(e.ids['rule-editor'].hidden,true);assert.ok(e.controls.every(c=>c.disabled));
    assert.equal(e.moves.length,0);
});

test('結果ページでも別Roundの結果に変わった場合だけModelを取得し直す', async () => {
 const e=environment('round-result');e.load('status-polling.js');e.start();
 await e.reply(selecting({matchState:'ROUND_RESULT',displayMatchState:'ROUND_RESULT',displayRoundNumber:2}));
 assert.deepEqual(e.moves,['/round-result?matchId=M']);
});
