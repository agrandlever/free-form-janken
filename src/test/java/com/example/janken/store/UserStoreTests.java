package com.example.janken.store;

import com.example.janken.domain.GameUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserStoreTests {

    @Test
    void savesFindsAndDeletesUserById() {
        UserStore store = new UserStore();
        GameUser user = new GameUser(UUID.randomUUID(), "ユーザー", Instant.EPOCH);

        assertTrue(store.findById(user.getId()).isEmpty());
        store.save(user);
        assertSame(user, store.findById(user.getId()).orElseThrow());
        store.deleteById(user.getId());
        assertTrue(store.findById(user.getId()).isEmpty());
        assertTrue(store.findAll().isEmpty());
        assertDoesNotThrow(() -> store.deleteById(user.getId()));
    }

    @Test
    void replacesReferenceForSameId() {
        UserStore store = new UserStore();
        UUID id = UUID.randomUUID();
        GameUser first = new GameUser(id, "ユーザー", Instant.EPOCH);
        GameUser replacement = new GameUser(id, "ユーザー", Instant.EPOCH);

        store.save(first);
        store.save(replacement);

        assertSame(replacement, store.findById(id).orElseThrow());
        assertEquals(List.of(replacement), store.findAll());
    }

    @Test
    void listIsAnUnmodifiableCopyWithoutExposingMap() {
        UserStore store = new UserStore();
        GameUser first = new GameUser(UUID.randomUUID(), "ユーザー", Instant.EPOCH);
        GameUser second = new GameUser(UUID.randomUUID(), "ユーザー", Instant.EPOCH);
        store.save(first);
        List<GameUser> listed = store.findAll();
        store.save(second);

        assertEquals(List.of(first), listed);
        assertThrows(UnsupportedOperationException.class, listed::clear);
        assertEquals(2, store.findAll().size());
        assertTrue(store.findAll().containsAll(List.of(first, second)));
    }
}
