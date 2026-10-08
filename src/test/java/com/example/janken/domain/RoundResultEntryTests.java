package com.example.janken.domain;

import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoundResultEntryTests {

    @Test
    void holdsExactlyFourHistoryFieldsWithoutScore() {
        UUID userId = UUID.randomUUID();
        RoundResultEntry entry = new RoundResultEntry(userId, "参加者", "グー", true);
        assertEquals(userId, entry.getUserId());
        assertEquals("参加者", entry.getUsername());
        assertEquals("グー", entry.getHandName());
        assertTrue(entry.isWonRound());
        assertEquals(Set.of("userId", "username", "handName", "wonRound"),
                new HashSet<>(Arrays.stream(RoundResultEntry.class.getDeclaredFields()).map(field -> field.getName()).toList()));
    }

    @Test
    void cannotBeChangedOrSubclassed() {
        assertTrue(Modifier.isFinal(RoundResultEntry.class.getModifiers()));
        assertTrue(Arrays.stream(RoundResultEntry.class.getDeclaredFields()).allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(RoundResultEntry.class.getMethods()).anyMatch(method -> method.getName().startsWith("set")));
        assertFalse(new RoundResultEntry(UUID.randomUUID(), "参加者", "パー", false).isWonRound());
    }

}
