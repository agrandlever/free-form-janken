'use strict';
(() => {
    const button = document.getElementById('toggle-affinities');
    const table = document.getElementById('original-hand-affinities');
    if (!button || !table) return;
    // ②のままならフォームを触らず、入力中の値を保持する。
    document.addEventListener('janken:status', (event) => {
        if (event.detail.userState === 'ROOM_WAITING') return;
        const editor = document.getElementById('result-original-hand-editor');
        if (!editor) return;
        editor.hidden = true;
        editor.querySelectorAll('input, select, button').forEach(control => { control.disabled = true; });
        // ③→②の後も無効な操作を復活させず、明示的な再表示で最新フォームを取得する。
    });
    button.addEventListener('click', () => {
        // 表示だけを切り替える。サーバーへ状態変更を送信しない。
        table.hidden = !table.hidden;
        button.textContent = table.hidden ? 'オリジナル手の相性を表示' : 'オリジナル手の相性を非表示';
        button.setAttribute('aria-expanded', String(!table.hidden));
    });
})();
