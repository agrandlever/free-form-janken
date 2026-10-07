package com.example.janken.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Room {

    private final UUID id;
    private final String name;
    private UUID hostUserId;
    private final List<UUID> memberIds = new ArrayList<>();
    private int targetWins = 3;
    private boolean preventConsecutiveSameOriginalHand = false;
    private UUID currentMatchId;
    private UUID lastCompletedMatchId;

    public Room(UUID id, String name, UUID hostUserId) {
        // 参加者の登録は後続のServiceで行う。Listは追加した順序を維持する。
        this.id = id;
        this.name = name;
        this.hostUserId = hostUserId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(UUID hostUserId) {
        this.hostUserId = hostUserId;
    }

    public List<UUID> getMemberIds() {
        return memberIds;
    }

    public int getTargetWins() {
        return targetWins;
    }

    public void setTargetWins(int targetWins) {
        this.targetWins = targetWins;
    }

    public boolean isPreventConsecutiveSameOriginalHand() {
        return preventConsecutiveSameOriginalHand;
    }

    public void setPreventConsecutiveSameOriginalHand(boolean preventConsecutiveSameOriginalHand) {
        this.preventConsecutiveSameOriginalHand = preventConsecutiveSameOriginalHand;
    }

    public UUID getCurrentMatchId() {
        return currentMatchId;
    }

    public void setCurrentMatchId(UUID currentMatchId) {
        this.currentMatchId = currentMatchId;
    }

    public UUID getLastCompletedMatchId() {
        return lastCompletedMatchId;
    }

    public void setLastCompletedMatchId(UUID lastCompletedMatchId) {
        this.lastCompletedMatchId = lastCompletedMatchId;
    }
}
