package com.example.janken.domain;

import java.util.UUID;

public class MatchParticipant {

    private final UUID userId;
    private final String username;
    private final UUID originalHandId;
    private int score = 0;
    private boolean active = true;
    private HandSelection previousHand;

    public MatchParticipant(UUID userId, String username, UUID originalHandId) {
        // 開始時の名前・IDを固定し、進行中の値だけsetterで更新可能にする。
        this.userId = userId;
        this.username = username;
        this.originalHandId = originalHandId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public UUID getOriginalHandId() {
        return originalHandId;
    }

    public int getScore() {
        return score;
    }

    public boolean isActive() {
        return active;
    }

    public HandSelection getPreviousHand() {
        return previousHand;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setPreviousHand(HandSelection previousHand) {
        this.previousHand = previousHand;
    }
}
