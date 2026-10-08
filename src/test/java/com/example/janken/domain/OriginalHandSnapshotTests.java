package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OriginalHandSnapshotTests {

    @Test
    void holdsAllFields() {
        UUID handId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        OriginalHandSnapshot snapshot = new OriginalHandSnapshot(handId, ownerId, "炎",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.WIN);
        assertEquals(handId, snapshot.getHandId());
        assertEquals(ownerId, snapshot.getOwnerUserId());
        assertEquals("炎", snapshot.getName());
        assertEquals(HandRelation.WIN, snapshot.getVsRock());
        assertEquals(HandRelation.LOSE, snapshot.getVsScissors());
        assertEquals(HandRelation.DRAW, snapshot.getVsPaper());
        assertEquals(HandRelation.WIN, snapshot.getVsOriginal());
    }

    @Test
    void staysIndependentOfCurrentOriginalHand() {
        OriginalHand hand = new OriginalHand(UUID.randomUUID(), "炎",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.WIN);
        OriginalHandSnapshot snapshot = new OriginalHandSnapshot(hand.getId(), UUID.randomUUID(),
                hand.getName(), hand.getVsRock(), hand.getVsScissors(), hand.getVsPaper(), hand.getVsOriginal());
        hand.setName("水");
        hand.setVsRock(HandRelation.LOSE);
        hand.setVsScissors(HandRelation.DRAW);
        hand.setVsPaper(HandRelation.WIN);
        hand.setVsOriginal(HandRelation.LOSE);
        assertEquals("炎", snapshot.getName());
        assertEquals(HandRelation.WIN, snapshot.getVsRock());
        assertEquals(HandRelation.LOSE, snapshot.getVsScissors());
        assertEquals(HandRelation.DRAW, snapshot.getVsPaper());
        assertEquals(HandRelation.WIN, snapshot.getVsOriginal());
    }

    @Test
    void isFinalWithOnlyFinalFieldsAndNoSetters() {
        assertTrue(Modifier.isFinal(OriginalHandSnapshot.class.getModifiers()));
        assertEquals(7, OriginalHandSnapshot.class.getDeclaredFields().length);
        assertTrue(Arrays.stream(OriginalHandSnapshot.class.getDeclaredFields())
                .allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(OriginalHandSnapshot.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")));
    }

}
