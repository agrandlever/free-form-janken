package com.example.janken.domain;

import com.example.janken.domain.enums.MatchEndType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MatchResultSnapshot {

    private final UUID matchId;
    private final UUID roomId;
    private final String roomName;
    private final int targetWins;
    private final boolean preventConsecutiveSameOriginalHand;
    private final Map<UUID, String> participantNames;
    private final MatchEndType endType;
    private final List<UUID> winnerIds;
    private final Map<UUID, Integer> finalScores;
    private final List<RoundResult> roundHistory;
    private final List<OriginalHandSnapshot> originalHandAffinities;
    private final Instant finishedAt;

    public MatchResultSnapshot(UUID matchId, UUID roomId, String roomName, int targetWins,
            boolean preventConsecutiveSameOriginalHand, Map<UUID, String> participantNames,
            MatchEndType endType, List<UUID> winnerIds, Map<UUID, Integer> finalScores,
            List<RoundResult> roundHistory, List<OriginalHandSnapshot> originalHandAffinities,
            Instant finishedAt) {
        this.matchId = matchId;
        this.roomId = roomId;
        this.roomName = roomName;
        this.targetWins = targetWins;
        this.preventConsecutiveSameOriginalHand = preventConsecutiveSameOriginalHand;
        // Mapを別に確保してから変更不可にし、開始時の参加者の順序も維持する。
        this.participantNames = Collections.unmodifiableMap(new LinkedHashMap<>(participantNames));
        this.finalScores = Collections.unmodifiableMap(new LinkedHashMap<>(finalScores));
        // 名前と勝数が別の参加者集合を表さないための技術的な整合性確認。
        if (!this.participantNames.keySet().equals(this.finalScores.keySet())) {
            throw new IllegalArgumentException("participantNames and finalScores must have the same keys");
        }
        this.endType = endType;
        this.winnerIds = List.copyOf(winnerIds);
        // RoundResultのコンストラクタがEntryも再生成するため、入れ子の参照も共有しない。
        this.roundHistory = roundHistory.stream()
                .map(result -> new RoundResult(result.getRoundNumber(), result.getEntries(),
                        result.isHasWinner(), result.getDecidedAt()))
                .toList();
        this.originalHandAffinities = originalHandAffinities.stream()
                .map(hand -> new OriginalHandSnapshot(hand.getHandId(), hand.getOwnerUserId(),
                        hand.getName(), hand.getVsRock(), hand.getVsScissors(),
                        hand.getVsPaper(), hand.getVsOriginal()))
                .toList();
        this.finishedAt = finishedAt;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public int getTargetWins() {
        return targetWins;
    }

    public boolean isPreventConsecutiveSameOriginalHand() {
        return preventConsecutiveSameOriginalHand;
    }

    public Map<UUID, String> getParticipantNames() {
        return participantNames;
    }

    public MatchEndType getEndType() {
        return endType;
    }

    public List<UUID> getWinnerIds() {
        return winnerIds;
    }

    public Map<UUID, Integer> getFinalScores() {
        return finalScores;
    }

    public List<RoundResult> getRoundHistory() {
        return roundHistory;
    }

    public List<OriginalHandSnapshot> getOriginalHandAffinities() {
        return originalHandAffinities;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

}
