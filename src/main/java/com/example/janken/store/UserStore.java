package com.example.janken.store;

import com.example.janken.domain.GameUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ユーザーをメモリ内に保持する。状態確認・更新を含む操作全体は、
 * 呼び出し側が共有GameStateLockで保護する。Store自身はロックや業務判断を持たない。
 */
@Component
public class UserStore {

    private final Map<UUID, GameUser> users = new HashMap<>();

    /** 同じUUIDがある場合は、そのUUIDの参照を置き換える。 */
    public void save(GameUser user) {
        Objects.requireNonNull(user, "user");
        users.put(Objects.requireNonNull(user.getId(), "user.id"), user);
    }

    public Optional<GameUser> findById(UUID userId) {
        return Optional.ofNullable(users.get(Objects.requireNonNull(userId, "userId")));
    }

    /** 存在しないUUIDの削除は何もしない。 */
    public void deleteById(UUID userId) {
        users.remove(Objects.requireNonNull(userId, "userId"));
    }

    /**
     * 一覧の構造はコピーし、呼び出し側からMapを変更できないようにする。
     * 要素は元のDomain参照なので、状態の参照・更新には共有ロックが必要。
     * 並び順は保証しない。
     */
    public List<GameUser> findAll() {
        return List.copyOf(users.values());
    }
}
