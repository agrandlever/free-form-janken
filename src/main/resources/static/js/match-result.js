'use strict';
(() => {
    const button = document.getElementById('toggle-affinities');
    const table = document.getElementById('original-hand-affinities');
    if (!button || !table) return;
    button.addEventListener('click', () => {
        // 表示だけを切り替える。サーバーへ状態変更を送信しない。
        table.hidden = !table.hidden;
        button.textContent = table.hidden ? 'オリジナル手の相性を表示' : 'オリジナル手の相性を非表示';
        button.setAttribute('aria-expanded', String(!table.hidden));
    });
})();
