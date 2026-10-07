package com.example.janken.domain;

import com.example.janken.domain.enums.HandRelation;
import java.util.UUID;

public class OriginalHand {

    private final UUID id;
    private String name;
    private HandRelation vsRock;
    private HandRelation vsScissors;
    private HandRelation vsPaper;
    private HandRelation vsOriginal;

    public OriginalHand(UUID id, String name, HandRelation vsRock, HandRelation vsScissors, HandRelation vsPaper, HandRelation vsOriginal) {
        // IDと設定値をそのまま保持する。名前の整形や相性の検証はService/Formの責務。
        this.id = id;
        this.name = name;
        this.vsRock = vsRock;
        this.vsScissors = vsScissors;
        this.vsPaper = vsPaper;
        this.vsOriginal = vsOriginal;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public HandRelation getVsRock() {
        return vsRock;
    }

    public void setVsRock(HandRelation vsRock) {
        this.vsRock = vsRock;
    }

    public HandRelation getVsScissors() {
        return vsScissors;
    }

    public void setVsScissors(HandRelation vsScissors) {
        this.vsScissors = vsScissors;
    }

    public HandRelation getVsPaper() {
        return vsPaper;
    }

    public void setVsPaper(HandRelation vsPaper) {
        this.vsPaper = vsPaper;
    }

    public HandRelation getVsOriginal() {
        return vsOriginal;
    }

    public void setVsOriginal(HandRelation vsOriginal) {
        this.vsOriginal = vsOriginal;
    }
}
