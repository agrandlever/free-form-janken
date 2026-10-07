'use strict';
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
        'display-target-wins','display-prevent-consecutive','open-original-hand','original-hand-panel','toggle-affinities','original-hand-affinities']) {
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

module.exports = { environment, selecting };
