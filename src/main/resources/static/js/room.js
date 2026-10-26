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
    let latestStatus = null;
    let fetchingEditor = false;
    const ruleEditor = document.getElementById('rule-editor');
    // エラー再表示の入力も未保存の編集値として扱い、最初の定期更新で消さない。
    let ruleDirty = Boolean(ruleEditor.querySelector('[id$="errors"] li'));
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
        button.className = url === '/room/ready/cancel' ? 'btn btn-outline-secondary' : 'btn btn-primary';
        form.append(hidden, button);
        return form;
    };
    const ensureRules = (room) => {
        let form = ruleEditor.querySelector('form');
        if (form) return form;
        form = actionForm('/room/rules', 'ルール変更', room.id);
        const heading = document.createElement('p');
        heading.className = 'fw-semibold';
        heading.textContent = 'ルールの変更（ホストのみ・対戦中は変更不可）';
        form.insertBefore(heading, form.children[0]);
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
        preventLabel.append(prevent, document.createTextNode('同じオリジナル手の連続使用を不可にする'));
        form.insertBefore(label, form.lastChild); form.insertBefore(preventLabel, form.lastChild);
        ruleEditor.append(form);
        return form;
    };
    const updateStatusText = (id, text) => {
        const element = document.getElementById(id);
        // 同じ内容を2秒ごとに書き直すと、読み上げソフトが繰り返し通知するため避ける。
        if (element.textContent !== text) element.textContent = text;
    };
    const updatePreparation = (status) => {
        const create = document.getElementById('create-original-hand');
        const ready = document.getElementById('ready-action');
        if (status.userState === 'READY') create.hidden = true;
        ready.hidden = status.userState === 'ROOM_WAITING' && !create.hidden;
        updateStatusText('self-ready-status', status.userState === 'READY'
            ? 'あなたは準備完了です。設定を変えるときは「準備取消」を押してください。'
            : create.hidden ? '手を確認したら、準備完了を押しましょう。' : '対戦前に、自分の手を1つ作りましょう。');
        const waiting = status.roomState === 'PLAYING'
            ? '現在の対戦が終わるのを待っています。'
            : status.room.members.length < 2
                ? '参加者が2人必要です。ルーム名を共有して相手を招待してください。'
            : status.room.members.filter(m => m.userState === 'READY').length < 2
                ? status.userState === 'READY' ? '相手の準備完了を待っています。'
                    : '準備完了が2人必要です。手を保存して準備完了を押してください。'
                : status.room.hostUserId === page.dataset.selfUserId
                    ? '対戦を開始できます。' : 'ホストの対戦開始を待っています。';
        updateStatusText('ready-waiting-message', waiting);
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
            if (!allowed) {
                panel.hidden = true; open.setAttribute('aria-expanded', 'false');
                document.getElementById('create-original-hand').setAttribute('aria-expanded', 'false');
            }
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
            const parsedPage = parsed.querySelector('main[data-page="room"]');
            if (!parsedPage || parsedPage.dataset.roomId !== page.dataset.roomId
                    || parsedPage.dataset.userState !== 'ROOM_WAITING') return;
            const editor = parsed.querySelector('.original-hand');
            if (!editor?.querySelector('#original-hand-panel')) return;
            const savedPreview = parsed.getElementById('ready-saved-hand');
            const preview = document.getElementById('ready-saved-hand');
            // 入力欄ではなく、サーバーが描画した保存済みの手名だけを準備欄へ反映する。
            if (preview.hidden !== savedPreview.hidden || preview.textContent !== savedPreview.textContent) {
                preview.replaceWith(document.importNode(savedPreview, true));
            }
            // APIに独自フラグを追加せず、既存のhasOriginalHandで描画された表示を使う。
            // 作成状態の確認だけでは、本人が入力中のフォームを置換しない。
            document.getElementById('create-original-hand').hidden = parsed.getElementById('create-original-hand').hidden;
            if (!open || !panel) {
                page.querySelector('.original-hand').replaceWith(document.importNode(editor, true));
                window.initOriginalHandEditor();
            } else {
                open.textContent = parsed.getElementById('open-original-hand').textContent;
                document.getElementById('original-hand-status').textContent = parsed.getElementById('original-hand-status').textContent;
                const summary = document.getElementById('saved-hand-summary');
                const savedSummary = parsed.getElementById('saved-hand-summary');
                // 保存済みの要約だけを同期し、開いている編集フォームの下書きは触らない。
                if (summary.hidden !== savedSummary.hidden || summary.textContent !== savedSummary.textContent) {
                    summary.replaceWith(document.importNode(savedSummary, true));
                }
                const savedDelete = parsed.getElementById('original-hand-delete-form');
                const currentDelete = document.getElementById('original-hand-delete-form');
                if (savedDelete && !currentDelete) panel.append(document.importNode(savedDelete, true));
                else if (!savedDelete && currentDelete) currentDelete.remove();
            }
            updatePreparation(latestStatus);
        } catch (error) {
            // 次回確認で再取得する。
        } finally { fetchingEditor = false; }
    };
    document.addEventListener('janken:status', ({ detail: status }) => {
        latestStatus = status;
        if (status.currentRoomId !== page.dataset.roomId || !status.room
                || !['ROOM_WAITING', 'READY'].includes(status.userState)) return;
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
                member.userState === 'PLAYING' ? '（現在対戦中）' : '',
                member.userState === 'ROOM_WAITING' ? '（準備前）' : ''
            ]) {
                if (!label) continue;
                const mark = document.createElement('strong'); mark.textContent = label; row.append(mark);
            }
            return row;
        }));
        // 既存の状態だけを表示に使い、準備・開始を許可する条件は変えない。
        updateStatusText('room-status',
            `参加者 ${room.members.length}人・準備完了 ${room.members.filter(m => m.userState === 'READY').length}人`);
        const ready = document.getElementById('ready-action');
        const readyUrl = status.userState === 'READY' ? '/room/ready/cancel' : '/room/ready';
        // 同じ状態の定期更新ではフォームを作り直さず、Tab操作中のフォーカスを保つ。
        if (!ready.action || !ready.action.endsWith(readyUrl)) {
            const nextReady = actionForm(readyUrl, status.userState === 'READY' ? '準備取消' : '準備完了', room.id);
            nextReady.id = 'ready-action'; ready.replaceWith(nextReady);
        }
        updatePreparation(status);
        const running = status.roomState === 'PLAYING';
        document.getElementById('match-running').hidden = !running;
        const watch = document.getElementById('watch-match');
        if (running) watch.setAttribute('href', '/play?matchId=' + encodeURIComponent(status.currentMatchId));
        else watch.removeAttribute('href');
        const isHost = room.hostUserId === page.dataset.selfUserId;
        const start = document.getElementById('start-action');
        const canStart = isHost && status.roomState === 'WAITING'
                && room.members.filter(m => m.userState === 'READY').length >= 2;
        let startForm = start.querySelector('form');
        // ホスト移譲で新ホストになった画面にもフォームを作り、無効時も表示を維持する。
        if (isHost && !startForm) {
            startForm = actionForm('/room/start', '対戦開始', room.id);
            start.replaceChildren(startForm);
        } else if (!isHost && startForm) {
            start.replaceChildren();
            startForm = null;
        }
        if (startForm) {
            startForm.querySelector('input[name="roomId"]').value = room.id;
            startForm.querySelector('button').disabled = !canStart;
        }
        document.getElementById('display-target-wins').textContent = room.targetWins;
        document.getElementById('display-prevent-consecutive').textContent = room.preventConsecutiveSameOriginalHand ? '不可' : '可能';
        const canEditRules = isHost && !running;
        ruleEditor.hidden = !canEditRules;
        if (!canEditRules) ruleEditor.open = false;
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
