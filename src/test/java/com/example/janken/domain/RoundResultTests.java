package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoundResultTests {

    @Test
    void holdsAllResultValuesAndCopiesEntries() {
        RoundResultEntry entry = new RoundResultEntry(UUID.randomUUID(), "参加者", "炎", true);
        RoundResult result = new RoundResult(4, List.of(entry), true, Instant.EPOCH);
        assertEquals(4, result.getRoundNumber());
        assertTrue(result.isHasWinner());
        assertEquals(Instant.EPOCH, result.getDecidedAt());
        assertEquals(1, result.getEntries().size());
        RoundResultEntry stored = result.getEntries().getFirst();
        assertNotSame(entry, stored);
        assertEquals(entry.getUserId(), stored.getUserId());
        assertEquals(entry.getUsername(), stored.getUsername());
        assertEquals(entry.getHandName(), stored.getHandName());
        assertEquals(entry.isWonRound(), stored.isWonRound());
    }

    @Test
    void inputListChangesDoNotChangeResult() {
        List<RoundResultEntry> entries = new ArrayList<>();
        entries.add(new RoundResultEntry(UUID.randomUUID(), "参加者", "グー", false));
        RoundResult result = new RoundResult(1, entries, false, Instant.EPOCH);
        entries.clear();
        assertEquals(1, result.getEntries().size());
        assertFalse(result.isHasWinner());
    }

    @Test
    void entriesCannotBeChangedThroughGetter() {
        RoundResultEntry entry = new RoundResultEntry(UUID.randomUUID(), "参加者", "グー", false);
        RoundResult result = new RoundResult(1, List.of(entry), false, Instant.EPOCH);
        assertThrows(UnsupportedOperationException.class, () -> result.getEntries().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getEntries().set(0, entry));
        assertThrows(UnsupportedOperationException.class, () -> result.getEntries().add(entry));
    }

    @Test
    void resultIsFinalWithoutSetters() {
        assertTrue(Modifier.isFinal(RoundResult.class.getModifiers()));
        assertTrue(Arrays.stream(RoundResult.class.getDeclaredFields()).allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(RoundResult.class.getMethods()).anyMatch(method -> method.getName().startsWith("set")));
    }

}
