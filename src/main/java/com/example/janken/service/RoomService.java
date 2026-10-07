package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.Room;
import com.example.janken.domain.enums.UserState;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.RoomStore;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoomService {
    private final GameStateLock lock;
    private final RoomStore rooms;
    private final SessionUserAccess access;
    private final Clock clock;

    public RoomService(GameStateLock lock, RoomStore rooms, SessionUserAccess access, Clock clock) {
        this.lock = lock;
        this.rooms = rooms;
        this.access = access;
        this.clock = clock;
    }

    public void enterRoom(HttpSession session, String roomName) {
        synchronized (lock) {
            GameUser user = access.require(session);
            // 入力不正でも、状態・所属不一致の409を優先する。
            if (user.getState() != UserState.ROOM_NONE || user.getCurrentRoomId() != null) {
                throw GameOperationException.invalidState();
            }
            String name = NameInput.normalize(roomName, "roomName", "ルーム名");
            Room room = rooms.findByName(name).orElse(null);
            if (room != null && room.getMemberIds().size() >= 8) {
                throw new GameOperationException(409, "ROOM_FULL", "このルームは満員です。");
            }
            Instant enteredAt = clock.instant();
            if (room == null) {
                room = new Room(UUID.randomUUID(), name, user.getId());
                rooms.save(room);
            }
            room.getMemberIds().add(user.getId());
            user.setCurrentRoomId(room.getId());
            user.setJoinedRoomAt(enteredAt);
            user.setLastSeenAt(enteredAt);
            user.setState(UserState.ROOM_WAITING);
        }
    }

    public void leaveRoom(HttpSession session, String roomId) {
        synchronized (lock) {
            GameUser user = access.require(session);
            if (user.getState() != UserState.ROOM_WAITING || user.getCurrentRoomId() == null) {
                throw GameOperationException.invalidState();
            }
            UUID target;
            try {
                target = UUID.fromString(roomId == null ? "" : roomId);
                if (!target.toString().equalsIgnoreCase(roomId)) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException ex) {
                throw GameOperationException.validation("roomId", "ルームの指定が不正です。");
            }
            if (!target.equals(user.getCurrentRoomId())) { throw GameOperationException.invalidState(); }
            leaveRoom(user);
        }
    }

    /** ログアウトからも呼ぶ。外側のロックを解放せず、所属整理を完了する。 */
    void leaveRoom(GameUser user) {
        synchronized (lock) {
            if (user.getState() != UserState.ROOM_WAITING || user.getCurrentRoomId() == null) {
                throw GameOperationException.invalidState();
            }
            Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
            if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
            room.getMemberIds().remove(user.getId());
            user.setState(UserState.ROOM_NONE);
            user.setCurrentRoomId(null);
            // 時刻・本人情報は維持し、次の入室成功時に時刻を設定し直す。
            if (room.getMemberIds().isEmpty()) {
                rooms.deleteById(room.getId());
            } else if (user.getId().equals(room.getHostUserId())) {
                transferHost(room);
            }
        }
    }

    private void transferHost(Room room) {
        // memberIdsは参加順なので、同じ入室時刻でも順序が一意に決まる。
        room.setHostUserId(room.getMemberIds().getFirst());
    }
}
