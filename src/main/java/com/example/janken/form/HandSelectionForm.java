package com.example.janken.form;

/** 生の入力を保持する。手の型変換は対象・状態・未確定確認の後にServiceで行う。 */
public class HandSelectionForm {
    private String matchId;
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    private String roundNumber;
    public String getRoundNumber() { return roundNumber; }
    public void setRoundNumber(String roundNumber) { this.roundNumber = roundNumber; }
    private String type;
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    private String normalHand;
    public String getNormalHand() { return normalHand; }
    public void setNormalHand(String normalHand) { this.normalHand = normalHand; }
    private String originalHandId;
    public String getOriginalHandId() { return originalHandId; }
    public void setOriginalHandId(String originalHandId) { this.originalHandId = originalHandId; }
}
