'use strict';

const originalHandPanel = document.getElementById('original-hand-panel');
const openOriginalHand = document.getElementById('open-original-hand');
const closeOriginalHand = document.getElementById('close-original-hand');

openOriginalHand.addEventListener('click', () => {
    originalHandPanel.hidden = false;
    openOriginalHand.setAttribute('aria-expanded', 'true');
    document.getElementById('original-hand-name').focus();
});
closeOriginalHand.addEventListener('click', () => {
    // 入力を消さずに閉じる。もう一度開いたときも同じ値を編集できる。
    originalHandPanel.hidden = true;
    openOriginalHand.setAttribute('aria-expanded', 'false');
    openOriginalHand.focus();
});
