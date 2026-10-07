package com.example.janken.store;

import com.example.janken.domain.MatchResultSnapshot;
import com.example.janken.domain.enums.MatchEndType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchResultStoreTests {

    private MatchResultSnapshot value(UUID id) {
        return new MatchResultSnapshot(id, UUID.randomUUID(), "ルーム", 3, false, Map.of(), MatchEndType.ABORTED, List.of(), Map.of(), List.of(), List.of(), Instant.EPOCH);
    }

    @Test
    void savesFindsAndDeletesByMatchId() {
        MatchResultStore store = new MatchResultStore();
        MatchResultSnapshot match = value(UUID.randomUUID());
        assertTrue(store.findById(match.getMatchId()).isEmpty());
        store.save(match);
        assertSame(match, store.findById(match.getMatchId()).orElseThrow());
        store.deleteById(match.getMatchId());
        assertTrue(store.findById(match.getMatchId()).isEmpty());
        assertTrue(store.findAll().isEmpty());
        assertDoesNotThrow(() -> store.deleteById(match.getMatchId()));
    }

    @Test
    void replacesReferenceForSameMatchId() {
        MatchResultStore store = new MatchResultStore();
        UUID id = UUID.randomUUID();
        MatchResultSnapshot first = value(id);
        MatchResultSnapshot replacement = value(id);
        store.save(first);
        store.save(replacement);
        assertSame(replacement, store.findById(id).orElseThrow());
        assertEquals(List.of(replacement), store.findAll());
    }

    @Test
    void listIsAnUnmodifiableCopyIndependentOfStoreChanges() {
        MatchResultStore store = new MatchResultStore();
        MatchResultSnapshot first = value(UUID.randomUUID());
        MatchResultSnapshot second = value(UUID.randomUUID());
        store.save(first);
        List<MatchResultSnapshot> listed = store.findAll();
        store.save(second);
        assertEquals(List.of(first), listed);
        assertThrows(UnsupportedOperationException.class, listed::clear);
        assertEquals(2, store.findAll().size());
        assertTrue(store.findAll().containsAll(List.of(first, second)));
        store.deleteById(first.getMatchId());
        assertEquals(List.of(first), listed);
        assertEquals(List.of(second), store.findAll());
    }

    @Test
    void rejectsNullStoreInputsAndKeysConsistentlyWithExistingStores() {
        MatchResultStore store = new MatchResultStore();
        assertThrows(NullPointerException.class, () -> store.save(null));
        assertThrows(NullPointerException.class, () -> store.save(value(null)));
        assertThrows(NullPointerException.class, () -> store.findById(null));
        assertThrows(NullPointerException.class, () -> store.deleteById(null));
        assertTrue(store.findAll().isEmpty());
    }
}
