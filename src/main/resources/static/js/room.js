'use strict';

const copyButton = document.getElementById('copy-room-name');
copyButton.addEventListener('click', async () => {
    const message = document.getElementById('copy-message');
    try {
        // 表示用の名前だけを読み取る。hiddenの内部roomIdには触れない。
        const roomName = document.getElementById('display-room-name').textContent;
        await navigator.clipboard.writeText(roomName);
        message.textContent = 'ルーム名をコピーしました';
    } catch (error) {
        message.textContent = 'コピーできませんでした。ルーム名を選択してコピーしてください。';
    }
});
