package com.example.janken.form;

import com.example.janken.domain.enums.HandRelation;

public class OriginalHandForm {
    private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    private HandRelation vsRock;
    public HandRelation getVsRock() { return vsRock; }
    public void setVsRock(HandRelation vsRock) { this.vsRock = vsRock; }
    private HandRelation vsScissors;
    public HandRelation getVsScissors() { return vsScissors; }
    public void setVsScissors(HandRelation vsScissors) { this.vsScissors = vsScissors; }
    private HandRelation vsPaper;
    public HandRelation getVsPaper() { return vsPaper; }
    public void setVsPaper(HandRelation vsPaper) { this.vsPaper = vsPaper; }
    private HandRelation vsOriginal;
    public HandRelation getVsOriginal() { return vsOriginal; }
    public void setVsOriginal(HandRelation vsOriginal) { this.vsOriginal = vsOriginal; }
    private String returnPage;
    public String getReturnPage() { return returnPage; }
    public void setReturnPage(String returnPage) { this.returnPage = returnPage; }
    private String roomId;
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    private String resultMatchId;
    public String getResultMatchId() { return resultMatchId; }
    public void setResultMatchId(String resultMatchId) { this.resultMatchId = resultMatchId; }
}
