'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {environment,selecting}=require('./sync-test-support.cjs');
const ended=(extra={})=>selecting({userState:'ROOM_WAITING',currentMatchId:null,matchState:null,displayMatchState:'MATCH_RESULT',...extra});
function editor(){const e=environment('match-result');
    for(const id of ['close-original-hand','original-hand-name','result-original-hand-editor']){e.ids[id]={hidden:false,disabled:false,focus(){this.focused=true;},listeners:{},addEventListener(n,f){this.listeners[n]=f;},emit(n){this.listeners[n]();},querySelectorAll(){return e.controls;}};}
    e.ids['original-hand-panel'].hidden=true;e.ids['original-hand-affinities'].hidden=true;
    e.ids['open-original-hand'].focus=function(){this.focused=true;};e.load('match-result.js');e.load('original-hand.js');return e;
}
for(const label of ['自分のオリジナル手を編集','オリジナル手を作成'])test(label+'は相性表内で開閉し入力値を保持する',()=>{
    const e=editor();e.ids['open-original-hand'].textContent=label;e.ids['toggle-affinities'].emit('click');e.ids['open-original-hand'].emit('click');
    assert.equal(e.ids['original-hand-affinities'].hidden,false);assert.equal(e.ids['original-hand-panel'].hidden,false);assert.equal(e.ids['original-hand-name'].focused,true);
    e.ids['original-hand-name'].value='編集中';e.ids['close-original-hand'].emit('click');assert.equal(e.ids['original-hand-panel'].hidden,true);
    e.ids['open-original-hand'].emit('click');assert.equal(e.ids['original-hand-name'].value,'編集中');assert.equal(e.fetches.length,0);
});
test('②の2秒pollは編集中のフォームを再読み込みしない',async()=>{const e=editor();e.ids['open-original-hand'].emit('click');e.controls[0].value='入力中';e.load('status-polling.js');e.start();await e.reply(ended());assert.deepEqual(e.moves,[]);assert.equal(e.controls[0].value,'入力中');assert.equal(e.ids['original-hand-panel'].hidden,false);assert.ok(e.controls.every(c=>!c.disabled));});
test('③化したら編集領域を隠し送信コントロールを無効化する',async()=>{const e=editor();e.ids['open-original-hand'].emit('click');e.load('status-polling.js');e.start();await e.reply(ended({userState:'READY'}));assert.equal(e.ids['result-original-hand-editor'].hidden,true);assert.ok(e.controls.every(c=>c.disabled));assert.deepEqual(e.moves,[]);});
for(const state of ['SELECTING_HAND','ROUND_RESULT'])test('④化したら編集中でも現在対戦の'+state+'へ移動する',async()=>{const e=editor();e.ids['open-original-hand'].emit('click');e.load('status-polling.js');e.start();await e.reply(selecting({currentMatchId:'M2',matchState:state,displayMatchState:'MATCH_RESULT'}));assert.deepEqual(e.moves,[(state==='SELECTING_HAND'?'/play':'/round-result')+'?matchId=M2']);});
