package com.example.janken.store;

import com.example.janken.domain.MatchResultSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** メモリ内の保存のみを担当する。呼び出し側が共有GameStateLockで操作全体を保護する。 */
@Component
public class MatchResultStore {

    private final Map<UUID, MatchResultSnapshot> results = new HashMap<>();

    /** 同じmatchIdの参照を置き換える。保存の可否などの業務判断は呼び出し側で行う。 */
    public void save(MatchResultSnapshot match) {
        Objects.requireNonNull(match, "match");
        results.put(Objects.requireNonNull(match.getMatchId(), "matchId"), match);
    }

    public Optional<MatchResultSnapshot> findById(UUID matchId) {
        return Optional.ofNullable(results.get(Objects.requireNonNull(matchId, "matchId")));
    }

    /** 存在しないIDの削除は何もしない。 */
    public void deleteById(UUID matchId) {
        results.remove(Objects.requireNonNull(matchId, "matchId"));
    }

    /** 一覧の構造だけをコピーする。並び順は保証しない。 */
    public List<MatchResultSnapshot> findAll() {
        return List.copyOf(results.values());
    }
}
