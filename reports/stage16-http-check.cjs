'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const base = 'http://localhost:18016';
const observations = [];
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const record = (check, data) => { observations.push({ check, ...data }); console.log(check, JSON.stringify(data)); };
function client() {
    let cookie = '';
    return async (path, data) => {
        const response = await fetch(base + path, { method: data == null ? 'GET' : 'POST', redirect: 'manual',
            headers: { ...(cookie ? { Cookie: cookie } : {}), ...(data == null ? {} : { 'Content-Type': 'application/x-www-form-urlencoded' }) },
            body: data == null ? undefined : new URLSearchParams(data) });
        const set = response.headers.get('set-cookie'); if (set) cookie = set.split(';')[0];
        const text = await response.text(); return { status: response.status, text, location: response.headers.get('location') };
    };
}
async function status(c, id) { const r = await c('/api/status' + (id ? '?matchId=' + id : ''), null); assert.equal(r.status, 200); return JSON.parse(r.text); }
async function enter(c, username, room) { assert.equal((await c('/login', { username })).status, 302); assert.equal((await c('/rooms/enter', { roomName: room })).status, 302); return status(c); }
async function main() {
    for (let n = 0; n < 30; n++) { try { if ((await fetch(base)).ok) break; } catch {} await wait(500); }
    const idle = client(), polling = client(), a = client(), b = client();
    const timeoutRoom = 'T' + Date.now(), schedulerRoom = 'S' + Date.now();
    const initial = await enter(idle, 'IdleUser', timeoutRoom); const lastHeartbeat = initial.serverTime;
    await enter(polling, 'PollingUser', timeoutRoom);
    const game = await enter(a, 'Winner', schedulerRoom); await enter(b, 'Loser', schedulerRoom);
    for (const [c, name] of [[a, 'WinnerHand'], [b, 'LoserHand']]) {
        assert.equal((await c('/original-hand/save', { returnPage: 'ROOM', roomId: game.currentRoomId, name, vsRock: 'WIN', vsScissors: 'LOSE', vsPaper: 'DRAW', vsOriginal: 'LOSE' })).status, 302);
        assert.equal((await c('/room/ready', { roomId: game.currentRoomId })).status, 302);
    }
    await a('/room/rules', { roomId: game.currentRoomId, targetWins: '1' });
    await a('/room/start', { roomId: game.currentRoomId }); const selecting = await status(a); const id = selecting.currentMatchId;
    for (const [c, normalHand] of [[a, 'ROCK'], [b, 'SCISSORS']]) assert.equal((await c('/play', { matchId: id, roundNumber: '1', type: 'NORMAL', normalHand })).status, 302);
    const round = await status(a, id); assert.equal(round.matchState, 'ROUND_RESULT');
    record('通常起動：最終Round結果', { state: round.userState, matchState: round.matchState, transitionAt: round.transitionAt });
    const start = Date.now(); let latest;
    while (Date.now() - start < 34000) {
        latest = await status(polling); assert.equal(latest.userState, 'ROOM_WAITING');
        await status(a, id); await status(b, id); await wait(2000);
    }
    record('正常2秒polling継続', { elapsedMilliseconds: Date.now() - start, state: latest.userState });
    const after = await status(idle); assert.equal(after.userState, 'ROOM_NONE'); assert.equal(after.room, null);
    record('状態確認停止後timeout・同じCookieのstatus200', { lastHeartbeat, now: after.serverTime, userState: after.userState, room: after.room });
    assert.equal(new URL((await idle('/room', null)).location, base).pathname, '/rooms'); assert.equal((await idle('/rooms', null)).status, 200);
    record('timeout後の画面アクセス', { roomRedirect: '/rooms', roomsStatus: 200 });
    const result = await status(a, id); assert.equal(result.displayMatchState, 'MATCH_RESULT'); assert.equal(result.userState, 'ROOM_WAITING');
    const html = await a('/match-result?matchId=' + id, null); assert.equal(html.status, 200); assert.ok(html.text.includes('Winner'));
    assert.ok(!html.text.includes('参加人数が不足')); record('通常起動：500ms Scheduler正常終了', { state: result.userState, displayMatchState: result.displayMatchState, resultStatus: html.status });
}
main().then(() => fs.writeFileSync('reports/stage16-http-observations.json', JSON.stringify({ result: 'PASS', observations }, null, 2)))
    .catch(error => { console.error(error); fs.writeFileSync('reports/stage16-http-observations.json', JSON.stringify({ result: 'FAIL', error: error.stack, observations }, null, 2)); process.exitCode = 1; });