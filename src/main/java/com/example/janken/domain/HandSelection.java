package com.example.janken.domain;

import com.example.janken.domain.enums.NormalHandType;
import com.example.janken.domain.enums.SelectedHandType;
import java.util.UUID;

public class HandSelection {

    private SelectedHandType type;
    private NormalHandType normalHand;
    private UUID originalHandId;

    public HandSelection(SelectedHandType type, NormalHandType normalHand, UUID originalHandId) {
        // 通常手・オリジナル手の値を保持するだけで、組み合わせの検証は行わない。
        this.type = type;
        this.normalHand = normalHand;
        this.originalHandId = originalHandId;
    }

    public SelectedHandType getType() {
        return type;
    }

    public void setType(SelectedHandType type) {
        this.type = type;
    }

    public NormalHandType getNormalHand() {
        return normalHand;
    }

    public void setNormalHand(NormalHandType normalHand) {
        this.normalHand = normalHand;
    }

    public UUID getOriginalHandId() {
        return originalHandId;
    }

    public void setOriginalHandId(UUID originalHandId) {
        this.originalHandId = originalHandId;
    }
}
