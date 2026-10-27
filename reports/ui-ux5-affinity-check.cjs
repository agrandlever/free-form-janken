'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fields = ['vsRock', 'vsScissors', 'vsPaper', 'vsOriginal'];
const groupMessage = '相性4項目のうち最低1項目は負けにしてください。';
const count = (text, needle) => text.split(needle).length - 1;
const tag = (html, id) => html.match(new RegExp('<[^>]+id="'+id+'"[^>]*>'))?.[0] ?? '';

module.exports = async function checkAffinities(c, target, record) {
    const path = target.returnPage === 'ROOMS' ? '/rooms' : target.returnPage === 'ROOM' ? '/room' : '/match-result?matchId='+target.resultMatchId;
    const valid = { ...target, name:'保存済み', vsRock:'LOSE', vsScissors:'WIN', vsPaper:'WIN', vsOriginal:'WIN' };
    assert.equal((await c('/original-hand/save',valid)).status,302);
    const saved=(await c(path)).text;
    const summary=saved.match(/<div id="saved-hand-summary"[\s\S]*?<\/dl>/)[0];
    const past=saved.match(/<table class="affinity-table[\s\S]*?<\/table>/)?.[0];
    for(const draw of [false,true]) for(const badName of [false,true]) {
        const input={...valid,name:badName?'':'未保存入力',vsRock:'WIN',vsPaper:draw?'DRAW':'WIN'};
        const r=await c('/original-hand/save',input);
        assert.equal(r.status,400); const html=r.text;
        assert.equal(count(html,groupMessage),1);
        assert.equal(count(html,'相性4項目を確認する'),1);
        assert.ok(html.includes('href="#affinity-fields"'));
        assert.ok(tag(html,'affinity-fields').includes('affinity-group-error'));
        assert.ok(tag(html,'affinity-fields').includes('role="group"'));
        assert.ok(tag(html,'affinity-fields').includes('tabindex="-1"'));
        assert.ok(html.includes('id="affinity-group-error"'));
        assert.ok(!html.includes('href="#vsOriginal-WIN"'));
        for(const field of fields) for(const value of ['WIN','LOSE','DRAW']) assert.ok(!tag(html,field+'-'+value).includes('aria-invalid'));
        assert.equal(tag(html,'original-hand-name').includes('aria-invalid="true"'),badName);
        assert.equal(html.match(/<div id="saved-hand-summary"[\s\S]*?<\/dl>/)[0],summary);
        assert.ok(!tag(html,'original-hand-panel').includes('hidden'));
        if(past) assert.equal(html.match(/<table class="affinity-table[\s\S]*?<\/table>/)[0],past);
        if(!draw&&!badName) fs.writeFileSync('reports/ui-ux5/error-'+target.returnPage+'.html',html);
    }
    for(const field of fields) for(const value of ['', 'INVALID']) {
        const input={...valid,name:'', [field]:value};
        const r=await c('/original-hand/save',input);
        assert.equal(r.status,400);
        assert.ok(tag(r.text,field+'-WIN').includes('aria-invalid="true"'));
        assert.ok(r.text.includes('href="#'+field+'-WIN"'));
        assert.equal(count(r.text,groupMessage),0);
        assert.ok(r.text.includes('href="#original-hand-name"'));
        if(value) assert.ok(r.text.includes('無効な選択値：INVALID'));
    }
    for(const field of fields) {
        const input={...target,name:'保存済み',...Object.fromEntries(fields.map(f=>[f,f===field?'LOSE':'WIN']))};
        assert.equal((await c('/original-hand/save',input)).status,302);
        const html=(await c(path)).text;
        for(const f of fields) assert.ok(tag(html,f+'-'+(f===field?'LOSE':'WIN')).includes('checked="checked"'));
    }
    assert.equal((await c('/original-hand/save',valid)).status,302);
    record('相性エラー分類・全WIN/DRAW・名前併存・各項目の未選択/不正値・1つLOSEの4通り・保存値保持',{screen:target.returnPage});
};
