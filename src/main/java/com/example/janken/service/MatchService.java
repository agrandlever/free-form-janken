package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.UserState;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.MatchStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MatchService {
    private final GameStateLock lock;
    private final MatchStore matches;
    private final Clock clock;
    public MatchService(GameStateLock lock, MatchStore matches, Clock clock) {
        this.lock = lock;
        this.matches = matches;
        this.clock = clock;
    }

    /** RoomServiceだけが呼ぶ。検証から登録まで外側の共有ロックを保持する。 */
    UUID startMatch(Room room, List<GameUser> readyParticipants) {
        if (!Thread.holdsLock(lock)) {
            throw new IllegalStateException("対戦開始にはGameStateLockが必要です。");
        }
        GameMatch match = new GameMatch(UUID.randomUUID(), room.getId(), room.getName(),
                room.getTargetWins(), room.isPreventConsecutiveSameOriginalHand());
        for (GameUser user : readyParticipants) {
            OriginalHand hand = user.getOriginalHand();
            match.getParticipants().put(user.getId(),
                    new MatchParticipant(user.getId(), user.getUsername(), hand.getId()));
        }
        for (GameUser user : readyParticipants) {
            OriginalHand hand = user.getOriginalHand();
            // 現在の手への参照を残さず、開始時点の値を別オブジェクトへコピーする。
            match.getOriginalHands().add(new OriginalHandSnapshot(hand.getId(), user.getId(),
                    hand.getName(), hand.getVsRock(), hand.getVsScissors(), hand.getVsPaper(), hand.getVsOriginal()));
        }
        match.setCurrentRound(new Round(1, clock.instant()));
        // Clock取得を含む全生成の成功後にだけ、共有状態をこの順序で更新する。
        matches.save(match);
        room.setCurrentMatchId(match.getId());
        readyParticipants.forEach(user -> user.setState(UserState.PLAYING));
        return match.getId();
    }
}
