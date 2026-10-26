'use strict';
(() => {
    const summary = document.querySelector('.errors');
    if (!summary) return;
    // サーバーが開いて再表示したフォームの最初の修正欄へ移す。項目がなければ概要を読む。
    const invalid = document.querySelector('input[aria-invalid="true"]:not([disabled])');
    (invalid || summary).focus();
    summary.querySelectorAll('a[href^="#"]').forEach(link => {
        link.addEventListener('click', event => {
            const input = document.getElementById(link.getAttribute('href').slice(1));
            if (!input || input.disabled) return;
            event.preventDefault();
            input.focus();
        });
    });
})();
