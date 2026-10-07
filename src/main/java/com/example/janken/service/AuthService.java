package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.enums.UserState;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final GameStateLock lock;
    private final UserStore users;
    private final SessionUserAccess access;
    private final RoomService rooms;
    private final Clock clock;

    public AuthService(GameStateLock lock, UserStore users, SessionUserAccess access,
            RoomService rooms, Clock clock) {
        this.lock = lock;
        this.users = users;
        this.access = access;
        this.rooms = rooms;
        this.clock = clock;
    }

    public void login(HttpServletRequest request, String username) {
        synchronized (lock) {
            if (access.find(request.getSession(false)) != null) { throw GameOperationException.invalidState(); }
            String name = NameInput.normalize(username, "username", "ユーザー名");
            GameUser user = new GameUser(UUID.randomUUID(), name, clock.instant());
            // セッションの取得・関連付けも登録と同じ排他区間内で行う。
            request.getSession(true).setAttribute(SessionUserAccess.USER_ID, user.getId());
            users.save(user);
        }
    }

    public void logout(HttpSession session) {
        synchronized (lock) {
            GameUser user = access.require(session);
            if (user.getState() == UserState.ROOM_WAITING) {
                rooms.leaveRoom(user);
            } else if (user.getState() != UserState.ROOM_NONE || user.getCurrentRoomId() != null) {
                throw GameOperationException.invalidState();
            }
            session.invalidate();
            // GameUserは現在ログイン中のユーザーを管理するため、ログアウト後は保持しない。
            users.deleteById(user.getId());
        }
    }
}
