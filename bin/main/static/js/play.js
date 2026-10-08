'use strict';
(() => {
    const page = document.querySelector('main[data-page="play"]');
    const history = document.getElementById('round-history');
    const toggle = document.getElementById('toggle-round-history');
    toggle.addEventListener('click', () => {
        history.hidden = !history.hidden;
        toggle.textContent = history.hidden ? 'ラウンド履歴を表示' : 'ラウンド履歴を非表示';
        toggle.setAttribute('aria-expanded', String(!history.hidden));
    });
    // 観戦者には本人手という概念がないため、手UI同期を登録しない。
    if (page.dataset.matchParticipant === 'false') return;
    // 初期HTMLも含め、同じRoundの確定状態はtrueからfalseへ戻さない。
    let confirmed = page.dataset.selfHandConfirmed === 'true';
    document.addEventListener('janken:status', ({ detail: status }) => {
        if (status.displayMatchId !== page.dataset.matchId
                || status.displayRoundNumber !== Number(page.dataset.roundNumber)
                || status.displayMatchState !== 'SELECTING_HAND') return;
        if (status.selfHandConfirmed !== true || confirmed) return;
        confirmed = true;
        page.dataset.selfHandConfirmed = 'true';
        page.querySelectorAll('form[action="/play"] button').forEach(button => { button.disabled = true; });
        const message = document.getElementById('self-hand-status');
        message.replaceChildren(document.createTextNode('確定済みの手：'));
        const hand = document.createElement('strong');
        // 名前はHTMLとして挿入せず、利用者入力を文字として扱う。
        hand.textContent = status.selfSelectedHand.handName;
        message.append(hand, document.createTextNode('。他の参加者の確定を待っています。'));
    });
})();
