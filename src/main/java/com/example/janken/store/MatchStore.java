package com.example.janken.store;

import com.example.janken.domain.GameMatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** メモリ内の保存のみを担当する。呼び出し側が共有GameStateLockで操作全体を保護する。 */
@Component
public class MatchStore {

    private final Map<UUID, GameMatch> matches = new HashMap<>();

    /** 同じmatchIdの参照を置き換える。保存の可否などの業務判断は呼び出し側で行う。 */
    public void save(GameMatch match) {
        Objects.requireNonNull(match, "match");
        matches.put(Objects.requireNonNull(match.getId(), "matchId"), match);
    }

    public Optional<GameMatch> findById(UUID matchId) {
        return Optional.ofNullable(matches.get(Objects.requireNonNull(matchId, "matchId")));
    }

    /** 存在しないIDの削除は何もしない。 */
    public void deleteById(UUID matchId) {
        matches.remove(Objects.requireNonNull(matchId, "matchId"));
    }

    /** 一覧の構造だけをコピーする。並び順は保証しない。 */
    public List<GameMatch> findAll() {
        return List.copyOf(matches.values());
    }
}
