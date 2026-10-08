'use strict';
// 通常起動したサーバーを、独立した2つのCookie（ログイン識別情報）で確認する。
const http=require('node:http');const fs=require('node:fs');const assert=require('node:assert/strict');
const observations=[];
function client(){let cookie='';return (path,values)=>new Promise((resolve,reject)=>{
 const req=http.request({host:'127.0.0.1',port:8080,path,method:values===undefined?'GET':'POST',headers:{...(cookie?{Cookie:cookie}:{}),...(values===undefined?{}:{'Content-Type':'application/x-www-form-urlencoded'})}},res=>{let body='';res.setEncoding('utf8');res.on('data',s=>body+=s);res.on('end',()=>{if(res.headers['set-cookie'])cookie=res.headers['set-cookie'][0].split(';')[0];resolve({status:res.statusCode,location:res.headers.location,body});});});req.on('error',reject);req.end(values===undefined?undefined:new URLSearchParams(values).toString());});}
const hidden=(html,name)=>{const match=html.match(new RegExp('name="'+name+'"[^>]*value="([^"]*)"'));assert.ok(match,name);return match[1];};
const table=html=>html.match(/<table class="affinity-table">[\s\S]*?<\/table>/)[0];
const redirect=(r,url)=>{assert.equal(r.status,302,r.body);assert.ok(r.location.endsWith(url),r.location);};
const note=(step,result)=>observations.push({step,...result});
(async()=>{const a=client(),b=client();const roomName='stage14-http';
 for(const [c,name] of [[a,'HTTP-A'],[b,'HTTP-B']]){redirect(await c('/login',{username:name}),'/rooms');redirect(await c('/rooms/enter',{roomName}),'/room');}
 const rid=hidden((await a('/room')).body,'roomId');redirect(await a('/room/rules',{roomId:rid,targetWins:'1'}),'/room');
 for(const [c,name] of [[a,'ドラゴン'],[b,'フェニックス']]){redirect(await c('/original-hand/save',{returnPage:'ROOM',roomId:rid,name,vsRock:'WIN',vsScissors:'LOSE',vsPaper:'DRAW',vsOriginal:'DRAW'}),'/room');redirect(await c('/room/ready',{roomId:rid}),'/room');}
 const start=await a('/room/start',{roomId:rid});assert.equal(start.status,302);const mid=start.location.split('matchId=')[1];const url='/match-result?matchId='+mid;
 redirect(await a('/play',{matchId:mid,roundNumber:'1',type:'NORMAL',normalHand:'ROCK'}),'/play?matchId='+mid);redirect(await b('/play',{matchId:mid,roundNumber:'1',type:'NORMAL',normalHand:'SCISSORS'}),'/play?matchId='+mid);
 // 本番Schedulerの10秒期限を待つ。テスト用APIは追加しない。
 for(let i=0;i<30;i++){const status=JSON.parse((await a('/api/status?matchId='+mid)).body);if(status.displayMatchState==='MATCH_RESULT')break;await new Promise(r=>setTimeout(r,500));}
 let result=await a(url);assert.equal(result.status,200);const past=table(result.body);assert.ok(result.body.includes('自分のオリジナル手を編集'));assert.equal(hidden(result.body,'resultMatchId'),mid);note('終了・②編集導線・制御値',{status:result.status,matchId:mid,roomId:rid});
 const control={returnPage:'MATCH_RESULT',roomId:rid,resultMatchId:mid};const save={...control,name:'ドラゴン',vsRock:'LOSE',vsScissors:'WIN',vsPaper:'DRAW',vsOriginal:'DRAW'};
 redirect(await a('/original-hand/save',save),url);result=await a(url);assert.equal(table(result.body),past);assert.ok(result.body.includes('value="LOSE" selected="selected"'));note('現在手更新・同一結果・過去相性不変',{status:302});
 const invalid=await a('/original-hand/save',{...save,name:' グー '});assert.equal(invalid.status,400);assert.ok(invalid.body.includes('VALIDATION_ERROR'));assert.ok(invalid.body.includes('value=" グー "'));assert.equal(hidden(invalid.body,'resultMatchId'),mid);assert.equal(table(invalid.body),past);assert.ok(!invalid.body.match(/id="original-hand-panel"[^>]*hidden/));assert.ok(!invalid.body.match(/id="original-hand-affinities"[^>]*hidden/));note('入力エラー・入力値保持・フォーム展開・同一結果',{status:400});
 const relationError=await a('/original-hand/save',{...save,vsRock:'INVALID'});assert.equal(relationError.status,400);assert.ok(relationError.body.includes('value="INVALID" selected="selected"'));assert.equal(table(relationError.body),past);note('相性の不正値もHTMLへ保持',{status:400});
 redirect(await a('/original-hand/delete',control),url);result=await a(url);assert.equal(table(result.body),past);assert.ok(result.body.includes('オリジナル手を作成'));assert.ok(!result.body.includes('/original-hand/delete'));note('削除・同一結果・過去相性残存・作成導線',{status:302});
 assert.equal((await a('/original-hand/delete',control)).status,409);redirect(await a('/original-hand/save',save),url);assert.equal(table((await a(url)).body),past);note('未作成削除拒否・再作成後も過去相性不変',{status:409});
 redirect(await a('/room/ready',{roomId:rid}),'/room');result=await a(url);assert.equal(result.status,200);for(const text of ['自分のオリジナル手を編集','オリジナル手を作成','/original-hand/delete','/original-hand/save','original-hand-panel'])assert.ok(!result.body.includes(text),text);assert.equal(table(result.body),past);note('③は閲覧のみ・操作導線なし',{status:200});
 assert.equal((await a('/original-hand/save',save)).status,409);assert.equal((await a('/original-hand/delete',control)).status,409);note('③旧フォームの保存・削除拒否',{status:409});
 fs.writeFileSync('reports/stage14-http-observations.json',JSON.stringify({passed:true,roomName,matchId:mid,roomId:rid,observations},null,2));console.log(JSON.stringify({passed:true,roomName,matchId:mid,roomId:rid,checks:observations.length}));
})().catch(error=>{console.error(error);process.exitCode=1;});
