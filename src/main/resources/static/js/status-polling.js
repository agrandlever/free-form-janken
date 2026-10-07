'use strict';
(() => {
    const page = document.querySelector('main[data-page]');
    if (!page) return;
    let stopped = false;
    let navigating = false;
    const move = (url) => {
        if (navigating) return;
        navigating = true;
        window.location.assign(url);
    };
    const matchUrl = (state, id) => {
        if (state === 'SELECTING_HAND') return '/play?matchId=' + encodeURIComponent(id);
        if (state === 'ROUND_RESULT') return '/round-result?matchId=' + encodeURIComponent(id);
        return null; // 対戦終了画面は第12段階で導入する。
    };
    const sync = (status) => {
        const kind = page.dataset.page;
        if (status.userState === 'ROOM_NONE') {
            if (kind !== 'rooms') move('/rooms');
            return;
        }
        if (status.userState === 'PLAYING') {
            const canonical = matchUrl(status.matchState, status.currentMatchId);
            if (!canonical) return;
            if (kind === 'rooms' || kind === 'room' || page.dataset.matchId !== status.currentMatchId) {
                move(canonical);
                return;
            }
            // 同じタブの対象はdisplayで確認し、URLのmatchIdを書き換えない。
            if (status.displayMatchId !== page.dataset.matchId) return;
            const destination = matchUrl(status.displayMatchState, status.displayMatchId);
            if ((kind === 'play' && status.displayMatchState === 'ROUND_RESULT')
                    || (kind === 'round-result' && status.displayMatchState === 'SELECTING_HAND')) {
                move(destination);
                return;
            }
            if (((kind === 'play' && status.displayMatchState === 'SELECTING_HAND')
                    || (kind === 'round-result' && status.displayMatchState === 'ROUND_RESULT'))
                    && status.displayRoundNumber !== Number(page.dataset.roundNumber)) {
                // 新RoundはGET Modelを取り直し、連続使用制限もサーバーで再評価する。
                move(destination);
                return;
            }
        } else if (kind !== 'room' || page.dataset.roomId !== status.currentRoomId) {
            // 第11段階では②・③の観戦は導入しない。
            move('/room');
            return;
        }
        document.dispatchEvent(new CustomEvent('janken:status', { detail: status }));
    };

    const poll = async () => {
        if (stopped || navigating) return;
        const started = performance.now();
        try {
            const query = page.dataset.matchId ? '?matchId=' + encodeURIComponent(page.dataset.matchId) : '';
            const response = await fetch('/api/status' + query, {
                credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
            });
            if (stopped) return;
            if (response.status === 401) { move('/'); return; }
            if (response.ok) sync(await response.json());
        } catch (error) {
            // 一時的な通信失敗でも状態を推測せず、次の確認を続ける。
        } finally {
            // 同時pollを重ねず、通常は開始時刻から約2秒ごとに確認する。
            if (!stopped && !navigating) setTimeout(poll, Math.max(0, 2000 - (performance.now() - started)));
        }
    };
    window.addEventListener('pagehide', () => { stopped = true; });
    document.addEventListener('DOMContentLoaded', poll, { once: true });
})();
