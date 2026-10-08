'use strict';
(() => {
    const page = document.querySelector('main[data-page="room"]');
    const copyButton = document.getElementById('copy-room-name');
    copyButton.addEventListener('click', async () => {
        const message = document.getElementById('copy-message');
        try {
            await navigator.clipboard.writeText(document.getElementById('display-room-name').textContent);
            message.textContent = 'ルーム名をコピーしました';
        } catch (error) {
            message.textContent = 'コピーできませんでした。ルーム名を選択してコピーしてください。';
        }
    });
    let ruleDirty = false;
    let latestStatus = null;
    let fetchingEditor = false;
    const ruleEditor = document.getElementById('rule-editor');
    ruleEditor.addEventListener('input', () => { ruleDirty = true; });
    ruleEditor.addEventListener('change', () => { ruleDirty = true; });

    const actionForm = (url, label, roomId) => {
        const form = document.createElement('form');
        form.method = 'post'; form.action = url; form.className = 'app-form';
        const hidden = document.createElement('input');
        hidden.type = 'hidden'; hidden.name = 'roomId'; hidden.value = roomId;
        const button = document.createElement('button');
        button.type = 'submit'; button.textContent = label;
        // 自動更新で作り直すボタンにも、初期HTMLと同じBootstrapの色を適用する。
        button.className = url === '/room/ready' || url === '/room/start'
            ? 'btn btn-success' : url === '/room/ready/cancel' ? 'btn btn-outline-secondary' : 'btn btn-primary';
        form.append(hidden, button);
        return form;
    };
    const ensureRules = (room) => {
        let form = ruleEditor.querySelector('form');
        if (form) return form;
        form = actionForm('/room/rules', 'ルール変更', room.id);
        const label = document.createElement('label');
        label.textContent = '先取勝数'; label.className = 'form-label';
        const wins = document.createElement('input');
        wins.className = 'form-control'; wins.name = 'targetWins'; wins.type = 'text'; wins.inputMode = 'numeric';
        label.append(wins);
        const preventLabel = document.createElement('label');
        preventLabel.className = 'form-label';
        const prevent = document.createElement('input');
        prevent.className = 'form-check-input me-2';
        prevent.type = 'checkbox'; prevent.name = 'preventConsecutiveSameOriginalHand'; prevent.value = 'true';
        preventLabel.append(prevent, document.createTextNode('同一オリジナル手の連続使用を禁止する'));
        form.insertBefore(label, form.lastChild); form.insertBefore(preventLabel, form.lastChild);
        ruleEditor.append(form);
        return form;
    };
    const syncEditor = async (status) => {
        const allowed = status.userState === 'ROOM_WAITING';
        const open = document.getElementById('open-original-hand');
        const panel = document.getElementById('original-hand-panel');
        if (open && panel) {
            open.hidden = !allowed;
            open.disabled = !allowed;
            // 許可された入力値を保持し、READY中だけ編集・送信を無効化する。
            panel.querySelectorAll('input,select,button').forEach(control => { control.disabled = !allowed; });
            if (!allowed) { panel.hidden = true; open.setAttribute('aria-expanded', 'false'); }
            return;
        }
        if (!allowed || fetchingEditor) return;
        fetchingEditor = true;
        try {
            // 初期READY画面は本人相性の編集Modelを持たないため、許可を得た時だけ部分取得する。
            const response = await fetch('/room', { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok || latestStatus.userState !== 'ROOM_WAITING'
                    || latestStatus.currentRoomId !== page.dataset.roomId) return;
            const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
            if (latestStatus.userState !== 'ROOM_WAITING' || latestStatus.currentRoomId !== page.dataset.roomId) return;
            const editor = parsed.querySelector('.original-hand');
            if (!editor?.querySelector('#original-hand-panel')) return;
            page.querySelector('.original-hand').replaceWith(document.importNode(editor, true));
            window.initOriginalHandEditor();
        } catch (error) {
            // 次回確認で再取得する。
        } finally { fetchingEditor = false; }
    };
    document.addEventListener('janken:status', ({ detail: status }) => {
        if (status.currentRoomId !== page.dataset.roomId || !status.room
                || !['ROOM_WAITING', 'READY'].includes(status.userState)) return;
        latestStatus = status;
        const room = status.room;
        document.getElementById('display-room-name').textContent = room.name;
        const members = document.getElementById('room-members');
        members.replaceChildren(...room.members.map(member => {
            const row = document.createElement('li');
            const name = document.createElement('span');
            name.textContent = member.username; row.append(name);
            for (const label of [
                member.isHost ? '（ホスト）' : '',
                member.userState === 'READY' ? '（準備完了）' : '',
                member.userState === 'PLAYING' ? '（現在対戦中）' : ''
            ]) {
                if (!label) continue;
                const mark = document.createElement('strong'); mark.textContent = label; row.append(mark);
            }
            return row;
        }));
        const ready = document.getElementById('ready-action');
        const nextReady = actionForm(status.userState === 'READY' ? '/room/ready/cancel' : '/room/ready',
                status.userState === 'READY' ? '準備取消' : '準備完了', room.id);
        nextReady.id = 'ready-action'; ready.replaceWith(nextReady);
        const running = status.roomState === 'PLAYING';
        document.getElementById('match-running').hidden = !running;
        const watch = document.getElementById('watch-match');
        if (running) watch.setAttribute('href', '/play?matchId=' + encodeURIComponent(status.currentMatchId));
        else watch.removeAttribute('href');
        const isHost = room.hostUserId === page.dataset.selfUserId;
        const start = document.getElementById('start-action');
        const canStart = isHost && !running && room.members.filter(m => m.userState === 'READY').length >= 2;
        start.replaceChildren(...(canStart ? [actionForm('/room/start', '対戦開始', room.id)] : []));
        document.getElementById('display-target-wins').textContent = room.targetWins;
        document.getElementById('display-prevent-consecutive').textContent = room.preventConsecutiveSameOriginalHand ? 'ON' : 'OFF';
        const canEditRules = isHost && !running;
        ruleEditor.hidden = !canEditRules;
        const ruleForm = canEditRules ? ensureRules(room) : ruleEditor.querySelector('form');
        if (ruleForm) {
            ruleForm.querySelectorAll('input,button').forEach(control => { control.disabled = !canEditRules; });
            if (!ruleDirty) {
                ruleForm.querySelector('[name="targetWins"]').value = room.targetWins;
                ruleForm.querySelector('[type="checkbox"]').checked = room.preventConsecutiveSameOriginalHand;
            }
        }
        syncEditor(status);
        page.dataset.userState = status.userState;
    });
})();
