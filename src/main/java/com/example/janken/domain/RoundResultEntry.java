package com.example.janken.domain;

import java.util.UUID;

public final class RoundResultEntry {

    private final UUID userId;
    private final String username;
    private final String handName;
    private final boolean wonRound;

    public RoundResultEntry(UUID userId, String username, String handName, boolean wonRound) {
        this.userId = userId;
        this.username = username;
        this.handName = handName;
        this.wonRound = wonRound;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getHandName() {
        return handName;
    }

    public boolean isWonRound() {
        return wonRound;
    }

}
