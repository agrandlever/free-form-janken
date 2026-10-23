'use strict';
(() => {
    // READY→待機に変わったときに取得した編集部分にも、同じ開閉操作を設定する。
    const init = () => {
        const panel = document.getElementById('original-hand-panel');
        const open = document.getElementById('open-original-hand');
        const close = document.getElementById('close-original-hand');
        const create = document.getElementById('create-original-hand');
        if (!panel || !open || !close || open.dataset.initialized) return;
        open.dataset.initialized = 'true';
        const expand = () => {
            panel.hidden = false;
            open.setAttribute('aria-expanded', 'true');
            create?.setAttribute('aria-expanded', 'true');
            document.getElementById('original-hand-name').focus();
        };
        open.addEventListener('click', expand);
        if (create && !create.dataset.initialized) {
            create.dataset.initialized = 'true';
            // 上部から開くときも同じフォームを使い、入力位置を画面内へ移す。
            create.addEventListener('click', () => {
                document.getElementById('open-original-hand').click();
                document.getElementById('original-hand-name').scrollIntoView({ block: 'center', behavior: 'instant' });
            });
        }
        close.addEventListener('click', () => {
            // 入力を消さずに閉じる。再度開いても同じ値を編集できる。
            panel.hidden = true;
            open.setAttribute('aria-expanded', 'false');
            create?.setAttribute('aria-expanded', 'false');
            open.focus();
        });
    };
    window.initOriginalHandEditor = init;
    init();
})();
