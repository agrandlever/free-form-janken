package com.example.janken.domain;

import java.time.Instant;
import java.util.List;

public final class RoundResult {

    private final int roundNumber;
    private final List<RoundResultEntry> entries;
    private final boolean hasWinner;
    private final Instant decidedAt;

    public RoundResult(int roundNumber, List<RoundResultEntry> entries, boolean hasWinner, Instant decidedAt) {
        this.roundNumber = roundNumber;
        // 要素も再生成し、入力Listとは独立した変更不可の履歴を保持する。
        this.entries = entries.stream()
                .map(entry -> new RoundResultEntry(entry.getUserId(), entry.getUsername(),
                        entry.getHandName(), entry.isWonRound()))
                .toList();
        this.hasWinner = hasWinner;
        this.decidedAt = decidedAt;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public List<RoundResultEntry> getEntries() {
        return entries;
    }

    public boolean isHasWinner() {
        return hasWinner;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

}
