'use strict';
// 既存の実HTTP検証と同じNode標準APIを使い、Cookieを利用者ごとに分離する。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const base = 'http://127.0.0.1:18080';
const observations = [];
const checkAffinities = require('./ui-ux5-affinity-check.cjs');
const record = (check, data = {}) => { observations.push({ check, ...data }); console.log(check, JSON.stringify(data)); };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function client() {
    let cookie = '';
    return async (path, body) => {
        const r = await fetch(base + path, { method: body == null ? 'GET' : 'POST', redirect: 'manual',
            headers: { ...(cookie ? { Cookie: cookie } : {}), ...(body == null ? {} : { 'Content-Type': 'application/x-www-form-urlencoded' }) },
            body: body == null ? undefined : new URLSearchParams(body) });
        const set = r.headers.get('set-cookie'); if (set) cookie = set.split(';')[0];
        return { status: r.status, text: await r.text(), location: r.headers.get('location') };
    };
}
async function state(c, id) { const r = await c('/api/status' + (id ? '?matchId=' + id : '')); assert.equal(r.status,200); return JSON.parse(r.text); }
function privateHtml(text) { for(const key of ['vsRock','vsScissors','vsPaper','vsOriginal','selections']) assert.ok(!text.includes(key),key); }
function privateJson(text) { for(const key of ['vsRock','vsScissors','vsPaper','vsOriginal','selections']) assert.ok(!text.includes(key),key); }
async function main() {
    const a=client(), b=client(), watcher=client();
    const roomName='UX'+Date.now();
    for(const [c, username] of [[a,'同名の参加者'],[b,'参加者B'],[watcher,'同名の参加者']]) {
        assert.equal((await c('/login',{username})).status,302);
        if(c===a) await checkAffinities(c, {returnPage:'ROOMS'}, record);
        assert.equal((await c('/rooms/enter',{roomName})).status,302);
    }
    const rid=(await state(a)).currentRoomId;
    const form=(name, changes={})=>({returnPage:'ROOM',roomId:rid,name,vsRock:'LOSE',vsScissors:'WIN',vsPaper:'DRAW',vsOriginal:'LOSE',...changes});
    for(const [c,name] of [[a,'手A'],[b,'手B']]) assert.equal((await c('/original-hand/save',form(name))).status,302);
    await checkAffinities(a,{returnPage:'ROOM',roomId:rid},record);
    assert.equal((await a('/original-hand/save',form('手A'))).status,302);
    const invalid=await a('/original-hand/save',{returnPage:'ROOM',roomId:rid,name:'未保存の手'});
    assert.equal(invalid.status,400); assert.ok(!invalid.text.includes('VALIDATION_ERROR'));
    for(const field of ['グー','チョキ','パー','他のオリジナル手']) assert.ok(invalid.text.includes(field+'への相性を選んでください。'));
    assert.ok(invalid.text.includes('value="未保存の手"')); assert.ok(invalid.text.includes('保存した手：<strong>手A</strong>'));
    record('複数エラー・入力保持・保存済み手名の分離');
    for (const targetWins of ['0','100','','1.5','abc']) {
        const error=await a('/room/rules',{roomId:rid,targetWins});
        assert.equal(error.status,400);
        assert.ok(error.text.includes('aria-describedby="targetWins-hint targetWins-errors"'));
        assert.ok(error.text.includes('id="targetWins-hint">1～99の整数'));
        assert.ok(error.text.includes('value="'+targetWins+'"'));
        assert.equal((await state(a)).room.targetWins,3);
    }
    for (const targetWins of ['1','99']) {
        assert.equal((await a('/room/rules',{roomId:rid,targetWins})).status,302);
        assert.equal((await state(a)).room.targetWins,Number(targetWins));
    }
    record('先取勝数の範囲・整数検証・補足とエラーの関連付け・入力と保存値の分離');
    assert.equal((await a('/room/rules',{roomId:rid,targetWins:'2',preventConsecutiveSameOriginalHand:'true'})).status,302);
    for(const c of [a,b]) assert.equal((await c('/room/ready',{roomId:rid})).status,302);
    const readyHtml=(await a('/room')).text; assert.ok(!readyHtml.includes('id="open-original-hand"'));
    assert.equal((await a('/room/start',{roomId:rid})).status,302);
    const mid=(await state(a)).currentMatchId;
    const initial=(await a('/play?matchId='+mid)).text;
    assert.ok(initial.indexOf('id="hand-selection-title"') < initial.indexOf('参加者の詳細'));
    assert.ok(initial.includes('手A')); assert.ok(initial.includes('手B')); privateHtml(initial);
    const options=[...initial.matchAll(/name="originalHandId" value="([^"]+)"/g)].map(m=>m[1]); assert.equal(options.length,2);
    const pick=async(c,round,hand)=>c('/play',{matchId:mid,roundNumber:String(round),type:hand==='SHARED'?'ORIGINAL':'NORMAL',...(hand==='SHARED'?{originalHandId:options[1]}:{normalHand:hand})});
    assert.equal((await pick(a,1,'SHARED')).status,302);
    assert.equal((await pick(a,1,'ROCK')).status,409);
    const waiting=await state(b,mid); assert.equal(waiting.selfHandConfirmed,false); assert.equal(waiting.selfSelectedHand,null); privateJson(JSON.stringify(waiting));
    const self=await state(a,mid); assert.equal(self.selfSelectedHand.handName,'手B');
    privateHtml((await watcher('/play?matchId='+mid)).text);
    record('全員の固定手を選択可能・二重確定拒否・他人の未確定手と相性の非公開');
    async function awaitRound(next, seconds) {
        const st=await state(a,mid); assert.equal(st.displayMatchState,'ROUND_RESULT');
        const remaining=Date.parse(st.displayTransitionAt)-Date.parse(st.serverTime);
        assert.ok(remaining>seconds*1000-1500 && remaining<=seconds*1000,{remaining,seconds});
        const own=(await a('/round-result?matchId='+mid)).text;
        assert.ok(own.includes('勝利なし')); assert.ok(own.includes('class="self-label">あなた</strong>'));
        const watching=(await watcher('/round-result?matchId='+mid)).text;
        assert.ok(!watching.includes('class="self-label">あなた</strong>')); privateHtml(watching);
        const deadline=Date.parse(st.displayTransitionAt); let latest=st;
        while(latest.displayMatchState==='ROUND_RESULT' && Date.parse(latest.serverTime)<deadline+4000) {
            await delay(400); latest=await state(a,mid); await state(b,mid); await state(watcher,mid);
        }
        assert.equal(latest.displayMatchState,next);
        if(next==='SELECTING_HAND') assert.equal(latest.displayRoundNumber,Number(st.displayRoundNumber)+1);
        record('ラウンド自動遷移',{round:st.displayRoundNumber,expectedSeconds:seconds,remainingMilliseconds:remaining,destination:next});
    }
    assert.equal((await pick(b,1,'SHARED')).status,302); await awaitRound('SELECTING_HAND',5);
    const restricted=(await a('/play?matchId='+mid)).text; assert.ok(restricted.includes('連続使用不可'));
    assert.equal((await pick(a,2,'SHARED')).status,400);
    assert.equal((await pick(a,2,'ROCK')).status,302); assert.equal((await pick(b,2,'SCISSORS')).status,302);
    await awaitRound('SELECTING_HAND',10);
    // 通常手は連続して選べる。同じ設定で最終ラウンドへ進む。
    assert.equal((await pick(a,3,'ROCK')).status,302); assert.equal((await pick(b,3,'SCISSORS')).status,302);
    await awaitRound('MATCH_RESULT',10);
    const resultPath='/match-result?matchId='+mid;
    const result=(await a(resultPath)).text; assert.ok(result.includes('オリジナル手の相性を表示')); assert.ok(result.includes('class="self-label">あなた</strong>'));
    assert.ok(!(await watcher(resultPath)).text.includes('class="self-label">あなた</strong>'));
    await checkAffinities(a,{returnPage:'MATCH_RESULT',roomId:rid,resultMatchId:mid},record);
    const past=result.match(/<table class="affinity-table[\s\S]*?<\/table>/)[0];
    assert.equal((await a('/original-hand/save',form('新しい手',{returnPage:'MATCH_RESULT',resultMatchId:mid,vsRock:'WIN',vsScissors:'LOSE'}))).status,302);
    const edited=(await a(resultPath)).text;
    assert.equal(edited.match(/<table class="affinity-table[\s\S]*?<\/table>/)[0],past);
    assert.ok(edited.includes('新しい手'));
    assert.equal((await a('/match-result/return',{roomId:rid,matchId:mid})).status,302);
    assert.equal((await a('/room')).status,200); assert.equal((await a('/room/leave',{roomId:rid})).status,302);
    assert.equal((await state(a)).userState,'ROOM_NONE'); assert.ok((await a('/rooms')).text.includes('新しい手'));
    record('最終結果・同名観戦者の本人ラベルなし・過去相性の不変・ルーム復帰と退出');
    if(process.argv.includes('--preview')) {
        // 画面検証中も独立セッションで正常な状態確認を続け、結果のルームを保持する。
        fs.writeFileSync('reports/ui-ux5/preview.json',JSON.stringify({roomName,rid,mid},null,2));
        const timer=setInterval(()=>Promise.all([state(b,mid),state(watcher,mid)]).catch(console.error),2000);
        console.log('PREVIEW_READY');
        // 検証終了時は起動したプロセスをCtrl+Cで停止する。
        await new Promise(()=>{});
    }
    for(const c of [a,b,watcher]) assert.equal((await c('/logout',{})).status,302);
}
main().then(()=>fs.writeFileSync('reports/ui-ux5/http-observations.json',JSON.stringify({result:'PASS',observations},null,2)))
    .catch(error=>{console.error(error);fs.writeFileSync('reports/ui-ux5/http-observations.json',JSON.stringify({result:'FAIL',error:error.stack,observations},null,2));process.exitCode=1;});
