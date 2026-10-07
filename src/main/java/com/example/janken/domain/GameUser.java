package com.example.janken.domain;

import com.example.janken.domain.enums.UserState;
import java.time.Instant;
import java.util.UUID;

public class GameUser {

    private final UUID id;
    private final String username;
    private UserState state = UserState.ROOM_NONE;
    private UUID currentRoomId;
    private OriginalHand originalHand;
    private Instant joinedRoomAt;
    private Instant lastSeenAt;

    public GameUser(UUID id, String username, Instant lastSeenAt) {
        // ログイン時刻は呼び出し側から受け取り、Domain内で現在時刻を取得しない。
        this.id = id;
        this.username = username;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public UserState getState() {
        return state;
    }

    public void setState(UserState state) {
        this.state = state;
    }

    public UUID getCurrentRoomId() {
        return currentRoomId;
    }

    public void setCurrentRoomId(UUID currentRoomId) {
        this.currentRoomId = currentRoomId;
    }

    public OriginalHand getOriginalHand() {
        return originalHand;
    }

    public void setOriginalHand(OriginalHand originalHand) {
        this.originalHand = originalHand;
    }

    public Instant getJoinedRoomAt() {
        return joinedRoomAt;
    }

    public void setJoinedRoomAt(Instant joinedRoomAt) {
        this.joinedRoomAt = joinedRoomAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
