package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.store.UserStore;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 呼び出し側が共有GameStateLockを保持して利用するセッション識別部品。 */
@Component
public class SessionUserAccess {
    public static final String USER_ID = "gameUserId";
    private final UserStore users;

    public SessionUserAccess(UserStore users) { this.users = users; }

    public GameUser find(HttpSession session) {
        if (session == null) { return null; }
        try {
            Object id = session.getAttribute(USER_ID);
            return id instanceof UUID userId ? users.findById(userId).orElse(null) : null;
        } catch (IllegalStateException ex) {
            // 同じセッションの別要求が先にログアウトした場合も未ログインとして扱う。
            return null;
        }
    }

    public GameUser require(HttpSession session) {
        GameUser user = find(session);
        if (user == null) {
            throw new GameOperationException(401, "LOGIN_REQUIRED", "ログインしてください。");
        }
        return user;
    }
}
