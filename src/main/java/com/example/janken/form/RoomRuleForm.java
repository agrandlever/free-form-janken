package com.example.janken.form;

/** 整数変換をServiceまで遅らせ、権限・状態の判定を入力検証より先に行う。 */
public class RoomRuleForm {
    private String roomId;
    private String targetWins;
    private boolean preventConsecutiveSameOriginalHand;

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getTargetWins() { return targetWins; }
    public void setTargetWins(String targetWins) { this.targetWins = targetWins; }
    public boolean isPreventConsecutiveSameOriginalHand() { return preventConsecutiveSameOriginalHand; }
    public void setPreventConsecutiveSameOriginalHand(boolean value) { this.preventConsecutiveSameOriginalHand = value; }
}
