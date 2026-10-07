package com.example.janken.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Round {

    private final int roundNumber;
    private final Map<UUID, HandSelection> selections = new LinkedHashMap<>();
    private final Instant startedAt;

    public Round(int roundNumber, Instant startedAt) {
        this.roundNumber = roundNumber;
        this.startedAt = startedAt;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    /** 進行中のMapを返す。後続処理での参照・更新は共有GameStateLock内で行う。 */
    public Map<UUID, HandSelection> getSelections() {
        return selections;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
