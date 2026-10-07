package com.example.janken.store;

import com.example.janken.domain.GameMatch;
import com.example.janken.domain.enums.MatchEndType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchStoreTests {

    private GameMatch value(UUID id) {
        return new GameMatch(id, UUID.randomUUID(), "ルーム", 3, false);
    }

    @Test
    void savesFindsAndDeletesByMatchId() {
        MatchStore store = new MatchStore();
        GameMatch match = value(UUID.randomUUID());
        assertTrue(store.findById(match.getId()).isEmpty());
        store.save(match);
        assertSame(match, store.findById(match.getId()).orElseThrow());
        store.deleteById(match.getId());
        assertTrue(store.findById(match.getId()).isEmpty());
        assertTrue(store.findAll().isEmpty());
        assertDoesNotThrow(() -> store.deleteById(match.getId()));
    }

    @Test
    void replacesReferenceForSameMatchId() {
        MatchStore store = new MatchStore();
        UUID id = UUID.randomUUID();
        GameMatch first = value(id);
        GameMatch replacement = value(id);
        store.save(first);
        store.save(replacement);
        assertSame(replacement, store.findById(id).orElseThrow());
        assertEquals(List.of(replacement), store.findAll());
    }

    @Test
    void listIsAnUnmodifiableCopyIndependentOfStoreChanges() {
        MatchStore store = new MatchStore();
        GameMatch first = value(UUID.randomUUID());
        GameMatch second = value(UUID.randomUUID());
        store.save(first);
        List<GameMatch> listed = store.findAll();
        store.save(second);
        assertEquals(List.of(first), listed);
        assertThrows(UnsupportedOperationException.class, listed::clear);
        assertEquals(2, store.findAll().size());
        assertTrue(store.findAll().containsAll(List.of(first, second)));
        store.deleteById(first.getId());
        assertEquals(List.of(first), listed);
        assertEquals(List.of(second), store.findAll());
    }

    @Test
    void rejectsNullStoreInputsAndKeysConsistentlyWithExistingStores() {
        MatchStore store = new MatchStore();
        assertThrows(NullPointerException.class, () -> store.save(null));
        assertThrows(NullPointerException.class, () -> store.save(value(null)));
        assertThrows(NullPointerException.class, () -> store.findById(null));
        assertThrows(NullPointerException.class, () -> store.deleteById(null));
        assertTrue(store.findAll().isEmpty());
    }
}
