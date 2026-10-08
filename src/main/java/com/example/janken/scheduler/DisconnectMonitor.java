package com.example.janken.scheduler;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.enums.UserState;
import com.example.janken.service.RoomService;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.UserStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 通信期限だけを監視し、退出に伴う業務処理は既存Serviceへ委譲する。 */
@Component
@ConditionalOnProperty(name = "janken.disconnect-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class DisconnectMonitor {
    private final GameStateLock lock;
    private final UserStore users;
    private final RoomService rooms;
    private final Clock clock;

    public DisconnectMonitor(GameStateLock lock, UserStore users, RoomService rooms, Clock clock) {
        this.lock = lock;
        this.users = users;
        this.rooms = rooms;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 1000)
    public void runChecks() {
        synchronized (lock) {
            // 収集から退出完了まで同じロックを保持する。statusが先なら更新後の時刻を見る。
            for (GameUser user : users.findAll()) {
                UserState state = user.getState();
                if ((state == UserState.ROOM_WAITING || state == UserState.READY || state == UserState.PLAYING)
                        && user.getCurrentRoomId() != null
                        && !clock.instant().isBefore(user.getLastSeenAt().plusSeconds(30))) {
                    // 前のユーザーの退出で対戦が終了した場合も、各ユーザーの最新状態で処理する。
                    rooms.leaveRoom(user);
                }
            }
        }
    }
}
