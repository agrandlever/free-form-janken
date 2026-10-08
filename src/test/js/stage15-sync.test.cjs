'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { environment, selecting } = require('./sync-test-support.cjs');
const spectator = (extra={}) => selecting({userState:'ROOM_WAITING', selfHandConfirmed:null,selfSelectedHand:null,...extra});
for(const userState of ['ROOM_WAITING','READY']) {
 test(userState+'観戦playは同じdisplay対象を維持し手UIを更新しない',async()=>{
  const e=environment('play');e.page.dataset.matchParticipant='false';e.load('play.js');e.load('status-polling.js');e.start();
  await e.reply(spectator({userState,currentMatchId:'OTHER',lastCompletedMatchId:'OLD'}));
  assert.deepEqual(e.moves,[]);assert.equal(e.fetches[0].url,'/api/status?matchId=M');
  // 本人手情報が誤って到着しても観戦HTMLの手UI処理は実行されない。
  e.emit(selecting({selfHandConfirmed:true,selfSelectedHand:{handName:'秘密'}}));
  assert.equal(e.ids['self-hand-status'].textContent,'');assert.ok(e.page.buttons.every(b=>!b.disabled));
  e.ids['toggle-round-history'].emit('click');assert.equal(e.ids['round-history'].hidden,false);
 });
 for(const [kind,state,url] of [['play','ROUND_RESULT','/round-result'],['round-result','SELECTING_HAND','/play'],['play','MATCH_RESULT','/match-result'],['round-result','MATCH_RESULT','/match-result']]) {
  test(userState+' '+kind+'から'+state+'へ同じIDで同期',async()=>{
   const e=environment(kind);e.load('status-polling.js');e.start();await e.reply(spectator({userState,displayMatchState:state,lastCompletedMatchId:'NEW'}));
   assert.deepEqual(e.moves,[url+'?matchId=M']);
  });
 }
 test(userState+' roomは開始・次Round・結果・終了でも再観戦を強制しない',async()=>{
  const e=environment('room');e.load('room.js');e.load('status-polling.js');e.start();
  for(const state of ['SELECTING_HAND','ROUND_RESULT','SELECTING_HAND','MATCH_RESULT']) {
   const running=state!=='MATCH_RESULT';await e.reply(spectator({userState,currentMatchId:running?'M':null,roomState:running?'PLAYING':'WAITING',room:{id:'R',name:'R',hostUserId:'B',targetWins:3,preventConsecutiveSameOriginalHand:false,members:[]}}));
   assert.deepEqual(e.moves,[]);assert.equal(e.ids['match-running'].hidden,!running);
   assert.equal(e.ids['watch-match'].attributes.href,running?'/play?matchId=M':undefined);e.timeouts.shift().fn();
  }
 });
}
test('観戦中④化は古い対象より新しい本人対戦を優先',async()=>{
 for(const state of ['SELECTING_HAND','ROUND_RESULT']){const e=environment('play');e.load('status-polling.js');e.start();await e.reply(selecting({currentMatchId:'NEW',matchState:state}));assert.deepEqual(e.moves,[(state==='SELECTING_HAND'?'/play':'/round-result')+'?matchId=NEW']);}
});
test('観戦の退出・別Room・無効対象・ログアウトはアクセス制御を優先',async()=>{
 for(const [data,code,url] of [[spectator({userState:'ROOM_NONE'}),200,'/rooms'],[spectator({currentRoomId:'OTHER'}),200,'/room'],[spectator({displayMatchId:null,displayMatchState:null}),200,'/room'],[{},401,'/']]){const e=environment('play');e.load('status-polling.js');e.start();await e.reply(data,code);assert.deepEqual(e.moves,[url]);}
});
test('観戦中の新Round番号は同じ対象のGET Modelを取得',async()=>{const e=environment('play');e.load('status-polling.js');e.start();await e.reply(spectator({displayRoundNumber:2}));assert.deepEqual(e.moves,['/play?matchId=M']);});
test('観戦round-resultのカウント0は遷移を確定しない',()=>{const e=environment('round-result');e.load('round-result.js');e.emit(spectator({displayMatchState:'ROUND_RESULT',serverTime:'2026-10-08T00:00:00Z',displayTransitionAt:'2026-10-08T00:00:05Z'}));e.setTime(6000);e.intervals[0].fn();assert.ok(e.ids['transition-count'].textContent.includes('0 秒'));assert.deepEqual(e.moves,[]);});
test('別タブ旧結果とroomフォーム入力は観戦導線の同期後も維持',async()=>{
 const old=environment('match-result');old.page.dataset.matchId='OLD';old.load('status-polling.js');old.start();await old.reply(spectator({displayMatchId:'OLD',displayMatchState:'MATCH_RESULT'}));assert.deepEqual(old.moves,[]);
 const room=environment('room');room.controls[0].value='入力途中';room.load('room.js');room.emit(spectator({roomState:'PLAYING',room:{id:'R',name:'R',hostUserId:'B',targetWins:3,preventConsecutiveSameOriginalHand:false,members:[]}}));assert.equal(room.controls[0].value,'入力途中');assert.deepEqual(room.moves,[]);
});
