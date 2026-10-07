package com.example.janken.store;

import com.example.janken.domain.Room;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ルーム本体と名前索引をメモリ内に保持する。
 * 呼び出し側は共有GameStateLockで検索から更新完了までを保護する。
 * 名前のstrip・検証や入室可否の判断は、後続のServiceの責務。
 */
@Component
public class RoomStore {

    private final Map<UUID, Room> rooms = new HashMap<>();
    private final Map<String, UUID> roomIdByName = new HashMap<>();

    /** 同じUUIDの参照を置き換える場合も、古い名前索引を残さない。 */
    public void save(Room room) {
        Objects.requireNonNull(room, "room");
        UUID roomId = Objects.requireNonNull(room.getId(), "room.id");
        String name = Objects.requireNonNull(room.getName(), "room.name");
        UUID indexedId = roomIdByName.get(name);
        // 1つの名前索引で別UUIDの2件を表現できないため、破壊的な上書きを防ぐ。
        // 同名への入室・作成の業務判断はServiceで行い、これは索引の整合性だけを守る。
        if (indexedId != null && !indexedId.equals(roomId)) {
            throw new IllegalStateException("Room name index already points to another UUID");
        }

        Room previous = rooms.put(roomId, room);
        if (previous != null) {
            roomIdByName.remove(previous.getName(), roomId);
        }
        roomIdByName.put(name, roomId);
    }

    public Optional<Room> findById(UUID roomId) {
        return Optional.ofNullable(rooms.get(Objects.requireNonNull(roomId, "roomId")));
    }

    /** 呼び出し側でstrip済みの名前を受け取り、完全一致で検索する。 */
    public Optional<Room> findByName(String name) {
        UUID roomId = roomIdByName.get(Objects.requireNonNull(name, "name"));
        return roomId == null ? Optional.empty() : Optional.ofNullable(rooms.get(roomId));
    }

    public void deleteById(UUID roomId) {
        Room removed = rooms.remove(Objects.requireNonNull(roomId, "roomId"));
        if (removed != null) {
            // 削除したルームと同じUUIDの索引だけを削除する。
            roomIdByName.remove(removed.getName(), roomId);
        }
    }

    /** 一覧の構造だけをコピーする。Domain要素の参照・更新には共有ロックが必要。並び順は保証しない。 */
    public List<Room> findAll() {
        return List.copyOf(rooms.values());
    }
}
