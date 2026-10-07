'use strict';
(() => {
    const page = document.querySelector('main[data-page="round-result"]');
    const count = document.getElementById('transition-count');
    let serverTime = null;
    let transitionAt = null;
    let receivedAt = null;
    const render = () => {
        if (serverTime === null || transitionAt === null) return;
        // 端末の日時ではなく、直近serverTimeと単調増加時計の経過時間を使う。
        const elapsed = Math.max(0, performance.now() - receivedAt);
        const seconds = Math.max(0, Math.ceil((transitionAt - serverTime - elapsed) / 1000));
        count.textContent = (page.dataset.matchFinished === 'true' ? '対戦結果まで ' : '次のラウンドまで ')
                + seconds + ' 秒';
        // 0秒でも画面遷移しない。遷移はstatus-pollingのサーバー状態確認だけが行う。
    };
    document.addEventListener('janken:status', ({ detail: status }) => {
        if (status.displayMatchId !== page.dataset.matchId || status.displayMatchState !== 'ROUND_RESULT') return;
        const time = Date.parse(status.serverTime);
        const deadline = Date.parse(status.displayTransitionAt);
        if (!Number.isFinite(time) || !Number.isFinite(deadline)) return;
        serverTime = time; transitionAt = deadline; receivedAt = performance.now();
        render();
    });
    const timer = setInterval(render, 1000);
    window.addEventListener('pagehide', () => clearInterval(timer), { once: true });
})();
