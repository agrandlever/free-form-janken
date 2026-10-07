package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.Room;
import com.example.janken.domain.enums.UserState;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.RoomStore;
import com.example.janken.store.UserStore;
import com.example.janken.form.RoomRuleForm;
import java.util.ArrayList;
import java.util.List;
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
    private final UserStore users;

    public RoomService(GameStateLock lock, RoomStore rooms, SessionUserAccess access, Clock clock, UserStore users) {
        this.lock = lock;
        this.rooms = rooms;
        this.access = access;
        this.clock = clock;
        this.users = users;
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
            if (!isRoomState(user) || user.getCurrentRoomId() == null) {
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
            if (!isRoomState(user) || user.getCurrentRoomId() == null) {
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

    public void ready(HttpSession session, String roomId) {
        synchronized (lock) {
            GameUser user = access.require(session);
            requireCurrentRoomId(user, roomId);
            if (user.getState() != UserState.ROOM_WAITING) { throw GameOperationException.invalidState(); }
            if (user.getOriginalHand() == null) {
                throw new GameOperationException(409, "ORIGINAL_HAND_REQUIRED",
                        "オリジナル手を作成してから準備完了してください。");
            }
            Room room = requireRoom(user);
            // 一覧取得から状態変更までロックを保持し、後続要求が最新の③を比較する。
            List<GameUser> readyMembers = room.getMemberIds().stream()
                    .map(id -> users.findById(id).orElseThrow(GameOperationException::invalidState))
                    .filter(member -> room.getId().equals(member.getCurrentRoomId()))
                    .filter(member -> member.getState() == UserState.READY).toList();
            boolean usernameConflict = readyMembers.stream()
                    .anyMatch(member -> user.getUsername().equals(member.getUsername()));
            boolean handConflict = readyMembers.stream().anyMatch(member -> member.getOriginalHand() != null
                    && user.getOriginalHand().getName().equals(member.getOriginalHand().getName()));
            // 両条件を独立に調べ、表示順と主エラーコードを固定する。
            List<String> messages = new ArrayList<>();
            if (usernameConflict) { messages.add("準備完了中のプレイヤーとユーザー名が重複しています。"); }
            if (handConflict) { messages.add("準備完了中のプレイヤーとオリジナル手の名前が重複しています。"); }
            if (!messages.isEmpty()) {
                throw new GameOperationException(409,
                        usernameConflict ? "READY_USERNAME_CONFLICT" : "READY_HAND_NAME_CONFLICT", messages);
            }
            user.setState(UserState.READY);
        }
    }

    public void cancelReady(HttpSession session, String roomId) {
        synchronized (lock) {
            GameUser user = access.require(session);
            requireCurrentRoomId(user, roomId);
            if (user.getState() != UserState.READY) { throw GameOperationException.invalidState(); }
            requireRoom(user);
            user.setState(UserState.ROOM_WAITING);
        }
    }

    public void updateRules(HttpSession session, RoomRuleForm form) {
        updateRules(session, form, java.util.Map.of());
    }

    public void updateRules(HttpSession session, RoomRuleForm form, java.util.Map<String, List<String>> inputErrors) {
        synchronized (lock) {
            GameUser user = access.require(session);
            requireCurrentRoomId(user, form.getRoomId());
            Room room = requireRoom(user);
            if (!user.getId().equals(room.getHostUserId())) {
                throw new GameOperationException(403, "FORBIDDEN", "ルールを変更できるのはホストだけです。");
            }
            if (!isRoomState(user) || room.getCurrentMatchId() != null) {
                throw GameOperationException.invalidState();
            }
            if (!inputErrors.isEmpty()) {
                throw new GameOperationException(400, "VALIDATION_ERROR", "入力内容を確認してください。", inputErrors);
            }
            int targetWins;
            try {
                String value = form.getTargetWins();
                if (value == null || !value.matches("[+-]?[0-9]+")) { throw new NumberFormatException(); }
                targetWins = Integer.parseInt(value);
                if (targetWins < 1 || targetWins > 99) { throw new NumberFormatException(); }
            } catch (NumberFormatException ex) {
                throw GameOperationException.validation("targetWins", "先取勝数は1～99の整数で入力してください。");
            }
            // 全検証後だけ両ルールを反映。本人・他参加者のREADYには触れない。
            room.setTargetWins(targetWins);
            room.setPreventConsecutiveSameOriginalHand(form.isPreventConsecutiveSameOriginalHand());
        }
    }

    private boolean isRoomState(GameUser user) {
        return user.getState() == UserState.ROOM_WAITING || user.getState() == UserState.READY;
    }

    private void requireCurrentRoomId(GameUser user, String roomId) {
        if (user.getCurrentRoomId() == null || roomId == null
                || !user.getCurrentRoomId().toString().equalsIgnoreCase(roomId)) {
            throw GameOperationException.invalidState();
        }
    }

    private Room requireRoom(GameUser user) {
        Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
        if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
        return room;
    }

    private void transferHost(Room room) {
        // memberIdsは参加順なので、同じ入室時刻でも順序が一意に決まる。
        room.setHostUserId(room.getMemberIds().getFirst());
    }
}
