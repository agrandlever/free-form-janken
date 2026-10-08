'use strict';
const http=require('node:http');const fs=require('node:fs');const assert=require('node:assert/strict');
const observations=[];const ROOM='s15-'+Date.now();
function client(){let cookie='';return (path,values)=>new Promise((resolve,reject)=>{
 const req=http.request({host:'127.0.0.1',port:8085,path,method:values===undefined?'GET':'POST',headers:{...(cookie?{Cookie:cookie}:{}),...(values===undefined?{}:{'Content-Type':'application/x-www-form-urlencoded'})}},res=>{let body='';res.setEncoding('utf8');res.on('data',s=>body+=s);res.on('end',()=>{if(res.headers['set-cookie'])cookie=res.headers['set-cookie'][0].split(';')[0];resolve({status:res.statusCode,location:res.headers.location,body});});});req.on('error',reject);req.end(values===undefined?undefined:new URLSearchParams(values).toString());});}
const redirect=(r,url)=>{assert.equal(r.status,302,r.body);assert.ok(r.location.endsWith(url),r.location);};
const state=async c=>JSON.parse((await c('/api/status')).body);
const display=async(c,id)=>JSON.parse((await c('/api/status?matchId='+id)).body);
const note=(name,detail)=>{observations.push({name,detail});console.log(name);};
async function until(c,id,expected){const start=Date.now();while(Date.now()-start<15000){const s=await display(c,id);if(s.displayMatchState===expected)return s;await new Promise(r=>setTimeout(r,500));}throw Error('Scheduler timeout '+expected);}
const save=async(c,rid,name)=>redirect(await c('/original-hand/save',{returnPage:'ROOM',roomId:rid,name,vsRock:'LOSE',vsScissors:'WIN',vsPaper:'DRAW',vsOriginal:'LOSE'}),'/room');
const ready=async(c,rid)=>redirect(await c('/room/ready',{roomId:rid}),'/room');
const submit=async(c,id,roundNumber,normalHand)=>redirect(await c('/play',{matchId:id,roundNumber,type:'NORMAL',normalHand}),'/play?matchId='+id);
async function resultPreserved(c,id){const r=await c('/match-result?matchId='+id);assert.equal(r.status,200);assert.ok(r.body.includes('data-match-id="'+id+'"'));}
(async()=>{
 const a=client(),b=client(),c=client(),d=client();
 for(const [browser,name]of [[a,'A'],[b,'B']]){redirect(await browser('/login',{username:name}),'/rooms');redirect(await browser('/rooms/enter',{roomName:ROOM}),'/room');}
 const rid=(await state(a)).currentRoomId;
 redirect(await a('/room/rules',{roomId:rid,targetWins:1}),'/room');await save(a,rid,'秘密手A');await save(b,rid,'秘密手B');await ready(a,rid);await ready(b,rid);
 redirect(await a('/room/start',{roomId:rid}),'/play?matchId='+(await state(a)).currentMatchId);const old=(await state(a)).currentMatchId;
 await submit(a,old,1,'ROCK');await submit(b,old,1,'SCISSORS');await until(a,old,'MATCH_RESULT');note('M0正常終了',{matchId:old});
 redirect(await c('/login',{username:'C'}),'/rooms');redirect(await c('/rooms/enter',{roomName:ROOM}),'/room');await resultPreserved(c,old);
 redirect(await a('/room/rules',{roomId:rid,targetWins:2}),'/room');await ready(a,rid);await ready(b,rid);await a('/room/start',{roomId:rid});const mid=(await state(a)).currentMatchId;
 redirect(await d('/login',{username:'D'}),'/rooms');redirect(await d('/rooms/enter',{roomName:ROOM}),'/room');await save(d,rid,'手D');await ready(d,rid);
 for(const [browser,userState]of [[c,'ROOM_WAITING'],[d,'READY']]){
  const room=await browser('/room');assert.equal(room.status,200);assert.ok(room.body.includes('現在対戦中です'));assert.ok(room.body.includes('/play?matchId='+mid));
  const r=await browser('/play?matchId='+mid);assert.equal(r.status,200);assert.ok(r.body.includes('観戦中です'));assert.ok(r.body.includes('ルームへ戻る'));assert.ok(r.body.includes('data-match-id="'+mid+'"'));
  for(const secret of ['action="/play"','ルームを退出','グー','チョキ','パー','秘密手A','秘密手B','手D'])assert.ok(!r.body.includes(secret),secret);
  assert.equal((await state(browser)).userState,userState);
 }
 note('②・③観戦開始と途中入室確認',{matchId:mid,C:'ROOM_WAITING',D:'READY'});
 await submit(a,mid,1,'ROCK');
 for(const browser of [c,d]){
  const html=(await browser('/play?matchId='+mid)).body;const json=(await browser('/api/status?matchId='+mid)).body;
  for(const secret of ['vsRock','vsScissors','vsPaper','vsOriginal','selections','グー','秘密手A','秘密手B']){assert.ok(!html.includes(secret),secret);assert.ok(!json.includes(secret),secret);}
  const s=JSON.parse(json);assert.equal(s.displayMatchId,mid);assert.equal(s.selfHandConfirmed,null);assert.equal(s.selfSelectedHand,null);
  assert.equal((await browser('/play',{matchId:mid,roundNumber:1,type:'NORMAL',normalHand:'ROCK'})).status,409);
 }
 note('Aだけ確定：HTML／JSON非公開と②・③直接POST拒否',true);
 await submit(b,mid,1,'SCISSORS');
 for(const browser of [c,d]){redirect(await browser('/play?matchId='+mid),'/round-result?matchId='+mid);const r=await browser('/round-result?matchId='+mid);assert.equal(r.status,200);assert.ok(r.body.includes('グー'));assert.ok(r.body.includes('チョキ'));assert.ok(r.body.includes('ルームへ戻る'));assert.ok(!r.body.includes('ルームを退出'));}
 await c('/room');await resultPreserved(c,old);note('ROUND_RESULT公開・Cルーム戻り・別タブM0維持',true);
 await until(d,mid,'SELECTING_HAND');redirect(await d('/round-result?matchId='+mid),'/play?matchId='+mid);assert.equal((await display(d,mid)).displayRoundNumber,2);
 assert.equal((await c('/room')).status,200);assert.ok((await c('/room')).body.includes('対戦を見る'));await resultPreserved(c,old);note('Scheduler次Round：同じM1維持とroom継続',true);
 await submit(a,mid,2,'ROCK');await submit(b,mid,2,'SCISSORS');await until(d,mid,'MATCH_RESULT');
 redirect(await d('/round-result?matchId='+mid),'/match-result?matchId='+mid);await resultPreserved(d,mid);await resultPreserved(c,old);
 assert.equal((await state(c)).userState,'ROOM_WAITING');assert.equal((await state(d)).userState,'READY');assert.ok((await c('/room')).body.includes('<div id="match-running" hidden="hidden">'));
 note('NORMAL終了：同じM1結果・C②／D③・M0維持',true);
 await save(c,rid,'手C');await ready(c,rid);assert.equal((await state(c)).userState,'READY');redirect(await c('/room/ready/cancel',{roomId:rid}),'/room');
 redirect(await d('/room/ready/cancel',{roomId:rid}),'/room');assert.equal((await state(d)).userState,'ROOM_WAITING');note('観戦後のready／cancelReady',true);
 await ready(a,rid);await ready(b,rid);await a('/room/start',{roomId:rid});const aborted=(await state(a)).currentMatchId;await ready(d,rid);
 await c('/play?matchId='+aborted);await d('/play?matchId='+aborted);redirect(await a('/room/leave',{roomId:rid}),'/rooms');
 assert.equal((await display(d,aborted)).displayMatchState,'MATCH_RESULT');assert.equal((await state(c)).userState,'ROOM_WAITING');assert.equal((await state(d)).userState,'READY');
 const result=await d('/match-result?matchId='+aborted);assert.equal(result.status,200);assert.ok(result.body.includes('参加人数が不足したため対戦を終了しました'));await resultPreserved(c,old);note('ABORTED：観戦者をactiveへ数えずC②／D③維持',true);
 await d('/room');redirect(await d('/room/leave',{roomId:rid}),'/rooms');redirect(await d('/play?matchId='+aborted),'/rooms');redirect(await d('/logout',{}),'/');redirect(await d('/play?matchId='+aborted),'/');note('観戦後退出・ログアウトのアクセス制御',true);
 fs.writeFileSync('reports/stage15-http-observations.json',JSON.stringify({passed:true,observations},null,2));
})().catch(e=>{console.error(e);fs.writeFileSync('reports/stage15-http-observations.json',JSON.stringify({passed:false,error:e.stack,observations},null,2));process.exitCode=1;});
