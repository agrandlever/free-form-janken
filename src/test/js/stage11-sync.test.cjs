'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// DOM（HTML要素の構造）・通信・時間を制御し、遅延応答と時計差を再現する。
class Element {
    constructor(tag = 'div') {
        this.tagName = tag; this.children = []; this.dataset = {}; this.attributes = {};
        this.hidden = false; this.disabled = false; this.listeners = {}; this.value = ''; this._text = '';
    }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
    append(...nodes) { this.children.push(...nodes); nodes.forEach(n => { n.parent = this; }); }
    replaceChildren(...nodes) { this.children = []; this._text = ''; this.append(...nodes); }
    replaceWith(node) {
        if (this.parent) this.parent.children[this.parent.children.indexOf(this)] = node;
        if (this.id) this.owner.ids[this.id] = node;
        node.owner = this.owner; node.parent = this.parent;
    }
    insertBefore(node, before) {
        this.children.splice(this.children.indexOf(before), 0, node); node.parent = this;
    }
    get lastChild() { return this.children.at(-1); }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(name, fn) { (this.listeners[name] ??= []).push(fn); }
    emit(name, data) { for (const fn of this.listeners[name] ?? []) fn(data); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
    querySelectorAll(selector) {
        if (this.buttons && selector.includes('button')) return this.buttons;
        const all = this.children.flatMap(c => [c, ...c.querySelectorAll('*')]);
        const parts = selector.split(',').map(p => p.trim());
        return all.filter(c => parts.some(p => p === '*' || p === c.tagName
            || (p.startsWith('[name=') && c.name === p.slice(7, -2))
            || (p === '[type="checkbox"]' && c.type === 'checkbox')));
    }
}
function environment(kind, confirmed = false) {
    let now = 0;
    const page = new Element('main');
    page.dataset = { page: kind, matchId: 'M', roomId: 'R', roundNumber: '1', selfHandConfirmed: String(confirmed),
        matchFinished: 'false', selfUserId: 'A', userState: 'ROOM_WAITING' };
    if (kind === 'rooms' || kind === 'room') delete page.dataset.matchId;
    const ids = {};
    const listeners = {};
    const document = {
        querySelector: () => page,
        getElementById: id => ids[id] ?? null,
        addEventListener: (name, fn) => { (listeners[name] ??= []).push(fn); },
        dispatchEvent: event => { for (const fn of listeners[event.type] ?? []) fn(event); },
        createElement: tag => { const e = new Element(tag); e.owner = document; return e; },
        createTextNode: text => { const e = new Element('#text'); e.textContent = text; return e; },
        ids
    };
    for (const id of ['round-history','toggle-round-history','self-hand-status','transition-count','copy-room-name','copy-message',
        'rule-editor','display-room-name','room-members','ready-action','match-running','start-action',
        'display-target-wins','display-prevent-consecutive','open-original-hand','original-hand-panel']) {
        ids[id] = document.createElement('div'); ids[id].id = id;
    }
    ids['round-history'].hidden = true;
    ids['toggle-round-history'].textContent = 'ラウンド履歴を表示';
    page.buttons = [new Element('button'),new Element('button'),new Element('button'),new Element('button')];
    if (confirmed) page.buttons.forEach(b => { b.disabled = true; });
    const controls = [new Element('input'),new Element('select'),new Element('button')];
    ids['original-hand-panel'].append(...controls);
    const moves = [], timeouts = [], intervals = [], fetches = [], pending = [];
    const context = vm.createContext({
        document, performance: { now: () => now },
        window: { location: { assign: url => moves.push(url) }, addEventListener: (name, fn) => { (listeners[name] ??= []).push(fn); } },
        navigator: { clipboard: { writeText: async () => {} } },
        CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
        setTimeout: (fn, delay) => { timeouts.push({ fn, delay }); return timeouts.length; },
        setInterval: (fn, delay) => { intervals.push({ fn, delay }); return intervals.length; },
        clearInterval: () => {},
        fetch: (url, options) => { fetches.push({ url, options }); return new Promise(resolve => pending.push(resolve)); },
        Date, Number, Math, console
    });
    function load(file) { vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/js',file),'utf8'),context); }
    const emit = status => document.dispatchEvent({ type: 'janken:status', detail: status });
    const start = () => document.dispatchEvent({ type: 'DOMContentLoaded' });
    const reply = async (data, code = 200) => {
        pending.shift()({ status: code, ok: code === 200, json: async () => data });
        for (let i=0;i<8;i++) await Promise.resolve();
    };
    return {page, ids, controls, moves, timeouts, intervals, fetches, pending, load, emit, start, reply,
        setTime: value => { now=value; }};
}
function selecting(extra = {}) {
    return { userState:'PLAYING',currentRoomId:'R',currentMatchId:'M',matchState:'SELECTING_HAND',
        displayMatchId:'M',displayMatchState:'SELECTING_HAND',displayRoundNumber:1,
        selfHandConfirmed:false,selfSelectedHand:null, ...extra };
}
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
