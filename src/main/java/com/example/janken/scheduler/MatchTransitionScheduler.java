package com.example.janken.scheduler;

import com.example.janken.domain.GameMatch;
import com.example.janken.domain.enums.MatchState;
import com.example.janken.service.MatchService;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.MatchStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 時間経過の候補を確認し、既存Serviceへ遷移を委譲する。 */
@Component
@ConditionalOnProperty(name = "janken.match-transition.enabled", havingValue = "true", matchIfMissing = true)
public class MatchTransitionScheduler {
    private final GameStateLock lock;
    private final MatchStore matches;
    private final MatchService service;
    private final Clock clock;

    public MatchTransitionScheduler(GameStateLock lock, MatchStore matches, MatchService service, Clock clock) {
        this.lock = lock;
        this.matches = matches;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 500)
    public void runTransitions() {
        synchronized (lock) {
            Instant now = clock.instant();
            // Room削除後の正常終了待ちも拾うため、MatchStore全体を同じ共有ロック内で走査する。
            for (GameMatch match : matches.findAll()) {
                if (match.getState() == MatchState.ROUND_RESULT && match.getTransitionAt() != null
                        && !now.isBefore(match.getTransitionAt())) {
                    // Serviceも同じロックで最新状態・期限を再確認する。業務処理はここへ複製しない。
                    service.advanceMatch(match.getId());
                }
            }
        }
    }
}
