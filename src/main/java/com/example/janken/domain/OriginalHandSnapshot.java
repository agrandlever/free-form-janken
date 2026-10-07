package com.example.janken.domain;

import com.example.janken.domain.enums.HandRelation;
import java.util.UUID;

public final class OriginalHandSnapshot {

    private final UUID handId;
    private final UUID ownerUserId;
    private final String name;
    private final HandRelation vsRock;
    private final HandRelation vsScissors;
    private final HandRelation vsPaper;
    private final HandRelation vsOriginal;

    public OriginalHandSnapshot(UUID handId, UUID ownerUserId, String name, HandRelation vsRock, HandRelation vsScissors, HandRelation vsPaper, HandRelation vsOriginal) {
        // 変更可能なOriginalHandを保持せず、不変の値だけを受け取る。
        this.handId = handId;
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.vsRock = vsRock;
        this.vsScissors = vsScissors;
        this.vsPaper = vsPaper;
        this.vsOriginal = vsOriginal;
    }

    public UUID getHandId() {
        return handId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public HandRelation getVsRock() {
        return vsRock;
    }

    public HandRelation getVsScissors() {
        return vsScissors;
    }

    public HandRelation getVsPaper() {
        return vsPaper;
    }

    public HandRelation getVsOriginal() {
        return vsOriginal;
    }

}
