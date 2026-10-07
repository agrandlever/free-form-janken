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
        if (state === 'MATCH_RESULT') return '/match-result?matchId=' + encodeURIComponent(id);
        return null;
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
            if (kind === 'rooms' || kind === 'room' || kind === 'match-result' || page.dataset.matchId !== status.currentMatchId) {
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
        } else {
            if (page.dataset.roomId !== status.currentRoomId) { move('/room'); return; }
            if (status.displayMatchId === page.dataset.matchId && status.displayMatchState === 'MATCH_RESULT'
                    && (kind === 'play' || kind === 'round-result')) {
                // 最新結果へ置き換えず、このタブで扱っていた対戦の終了結果へ進む。
                move(matchUrl('MATCH_RESULT', page.dataset.matchId));
                return;
            }
            if (kind === 'match-result' && status.displayMatchId === page.dataset.matchId
                    && status.displayMatchState === 'MATCH_RESULT') {
                // ②・③は同じ結果を維持する。lastCompletedMatchIdは表示対象に使わない。
            } else if (kind !== 'room') { move('/room'); return; }
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
