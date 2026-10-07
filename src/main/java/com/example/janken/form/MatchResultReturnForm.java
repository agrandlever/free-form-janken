package com.example.janken.form;

/** 対象はタブのhidden値で保持し、セッションに共有の結果IDを保存しない。 */
public class MatchResultReturnForm {
    private String roomId;
    private String matchId;
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
}
