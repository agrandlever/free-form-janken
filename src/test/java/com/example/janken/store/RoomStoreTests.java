package com.example.janken.store;

import com.example.janken.domain.Room;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomStoreTests {

    @Test
    void savesAndFindsRoomByIdAndStrippedName() {
        RoomStore store = new RoomStore();
        // 入力整形は呼び出し側の責務。Storeへ渡す前にstripする。
        String name = "　 ルーム　 ".strip();
        Room room = new Room(UUID.randomUUID(), name, UUID.randomUUID());

        assertTrue(store.findById(room.getId()).isEmpty());
        assertTrue(store.findByName(name).isEmpty());
        store.save(room);

        assertSame(room, store.findById(room.getId()).orElseThrow());
        assertSame(room, store.findByName(name).orElseThrow());
        assertTrue(store.findByName(" " + name + " ").isEmpty());
    }

    @Test
    void deletionRemovesBothMapsAndAllowsIndexReuse() {
        RoomStore store = new RoomStore();
        Room room = new Room(UUID.randomUUID(), "ルーム", UUID.randomUUID());
        store.save(room);

        store.deleteById(room.getId());

        assertTrue(store.findById(room.getId()).isEmpty());
        assertTrue(store.findByName(room.getName()).isEmpty());
        assertTrue(store.findAll().isEmpty());
        assertDoesNotThrow(() -> store.deleteById(room.getId()));
        Room next = new Room(UUID.randomUUID(), room.getName(), UUID.randomUUID());
        store.save(next);
        assertSame(next, store.findByName(next.getName()).orElseThrow());
    }

    @Test
    void replacingSameIdUpdatesNameIndex() {
        RoomStore store = new RoomStore();
        UUID id = UUID.randomUUID();
        Room first = new Room(id, "旧名", UUID.randomUUID());
        Room replacement = new Room(id, "新名", UUID.randomUUID());
        store.save(first);
        store.save(first);
        assertSame(first, store.findByName(first.getName()).orElseThrow());

        store.save(replacement);

        assertTrue(store.findByName(first.getName()).isEmpty());
        assertSame(replacement, store.findById(id).orElseThrow());
        assertSame(replacement, store.findByName(replacement.getName()).orElseThrow());
        assertEquals(List.of(replacement), store.findAll());
    }

    @Test
    void conflictingIndexIsRejectedBeforeEitherMapChanges() {
        RoomStore store = new RoomStore();
        Room first = new Room(UUID.randomUUID(), "ルーム", UUID.randomUUID());
        Room conflicting = new Room(UUID.randomUUID(), first.getName(), UUID.randomUUID());
        store.save(first);

        assertThrows(IllegalStateException.class, () -> store.save(conflicting));

        assertSame(first, store.findById(first.getId()).orElseThrow());
        assertSame(first, store.findByName(first.getName()).orElseThrow());
        assertTrue(store.findById(conflicting.getId()).isEmpty());
        assertEquals(List.of(first), store.findAll());
        store.deleteById(conflicting.getId());
        assertSame(first, store.findByName(first.getName()).orElseThrow());
    }

    @Test
    void conflictingReplacementKeepsOriginalIndex() {
        RoomStore store = new RoomStore();
        Room first = new Room(UUID.randomUUID(), "ルームA", UUID.randomUUID());
        Room second = new Room(UUID.randomUUID(), "ルームB", UUID.randomUUID());
        store.save(first);
        store.save(second);
        Room conflicting = new Room(first.getId(), second.getName(), first.getHostUserId());

        assertThrows(IllegalStateException.class, () -> store.save(conflicting));

        assertSame(first, store.findById(first.getId()).orElseThrow());
        assertSame(first, store.findByName(first.getName()).orElseThrow());
        assertSame(second, store.findByName(second.getName()).orElseThrow());
        assertEquals(2, store.findAll().size());
    }

    @Test
    void listIsAnUnmodifiableCopyWithoutExposingMaps() {
        RoomStore store = new RoomStore();
        Room first = new Room(UUID.randomUUID(), "ルームA", UUID.randomUUID());
        Room second = new Room(UUID.randomUUID(), "ルームB", UUID.randomUUID());
        store.save(first);
        List<Room> listed = store.findAll();
        store.save(second);

        assertEquals(List.of(first), listed);
        assertThrows(UnsupportedOperationException.class, listed::clear);
        assertEquals(2, store.findAll().size());
        assertTrue(store.findAll().containsAll(List.of(first, second)));
    }
}
