package com.example.janken.domain;

import com.example.janken.domain.enums.MatchEndType;
import com.example.janken.domain.enums.MatchState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 進行中の状態・コレクションの参照と更新は共有GameStateLock内で行う。 */
public class GameMatch {

    private final UUID id;
    private final UUID roomId;
    private final String roomName;
    private MatchState state = MatchState.SELECTING_HAND;
    private final int targetWins;
    private final boolean preventConsecutiveSameOriginalHand;
    private final Map<UUID, MatchParticipant> participants = new LinkedHashMap<>();
    private final List<OriginalHandSnapshot> originalHands = new ArrayList<>();
    private Round currentRound;
    private final List<RoundResult> roundHistory = new ArrayList<>();
    private Instant transitionAt;
    private MatchEndType pendingEndType;
    private final List<UUID> pendingWinnerIds = new ArrayList<>();
    private MatchEndType endType;
    private final List<UUID> winnerIds = new ArrayList<>();

    public GameMatch(UUID id, UUID roomId, String roomName, int targetWins, boolean preventConsecutiveSameOriginalHand) {
        // Room参照は保持しない。参加者・手・Roundの設定は後続処理で行う。
        this.id = id;
        this.roomId = roomId;
        this.roomName = roomName;
        this.targetWins = targetWins;
        this.preventConsecutiveSameOriginalHand = preventConsecutiveSameOriginalHand;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public MatchState getState() {
        return state;
    }

    public int getTargetWins() {
        return targetWins;
    }

    public boolean isPreventConsecutiveSameOriginalHand() {
        return preventConsecutiveSameOriginalHand;
    }

    public Map<UUID, MatchParticipant> getParticipants() {
        return participants;
    }

    public List<OriginalHandSnapshot> getOriginalHands() {
        return originalHands;
    }

    public Round getCurrentRound() {
        return currentRound;
    }

    public List<RoundResult> getRoundHistory() {
        return roundHistory;
    }

    public Instant getTransitionAt() {
        return transitionAt;
    }

    public MatchEndType getPendingEndType() {
        return pendingEndType;
    }

    public List<UUID> getPendingWinnerIds() {
        return pendingWinnerIds;
    }

    public MatchEndType getEndType() {
        return endType;
    }

    public List<UUID> getWinnerIds() {
        return winnerIds;
    }

    public void setState(MatchState state) {
        this.state = state;
    }

    public void setCurrentRound(Round currentRound) {
        this.currentRound = currentRound;
    }

    public void setTransitionAt(Instant transitionAt) {
        this.transitionAt = transitionAt;
    }

    public void setPendingEndType(MatchEndType pendingEndType) {
        this.pendingEndType = pendingEndType;
    }

    public void setEndType(MatchEndType endType) {
        this.endType = endType;
    }
}
