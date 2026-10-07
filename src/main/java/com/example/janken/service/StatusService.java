package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.store.*;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 状態確認専用。共有状態から公開可能な値だけをコピーする。 */
@Service
public class StatusService {
    private final GameStateLock lock;
    private final SessionUserAccess access;
    private final UserStore users;
    private final RoomStore rooms;
    private final MatchStore matches;
    private final Clock clock;

    public StatusService(GameStateLock lock, SessionUserAccess access, UserStore users,
            RoomStore rooms, MatchStore matches, Clock clock) {
        this.lock = lock; this.access = access; this.users = users;
        this.rooms = rooms; this.matches = matches; this.clock = clock;
    }

    public record MemberView(UUID userId, String username, UserState userState, boolean isHost) { }
    public record RoomView(UUID id, String name, UUID hostUserId, int targetWins,
            boolean preventConsecutiveSameOriginalHand, List<MemberView> members) { }
    public record SelectedHandView(SelectedHandType type, NormalHandType normalHand,
            UUID originalHandId, String handName) { }
    public record StatusView(String serverTime, UserState userState, UUID currentRoomId,
            String roomState, UUID currentMatchId, MatchState matchState, Integer roundNumber,
            String transitionAt, UUID lastCompletedMatchId, UUID displayMatchId,
            MatchState displayMatchState, Integer displayRoundNumber, String displayTransitionAt,
            Boolean selfHandConfirmed, SelectedHandView selfSelectedHand, RoomView room) { }

    public StatusView status(HttpSession session, String matchId) {
        synchronized (lock) {
            GameUser user = access.require(session);
            // UUID形式不正ではlastSeenAtを更新しない。短縮UUIDも受け付けない。
            UUID requested = parseOptionalId(matchId);
            var now = clock.instant();
            Room room = user.getState() == UserState.ROOM_NONE || user.getCurrentRoomId() == null
                    ? null : rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
            if (room != null && !room.getMemberIds().contains(user.getId())) {
                throw GameOperationException.invalidState();
            }
            GameMatch current = room == null || room.getCurrentMatchId() == null ? null
                    : matches.findById(room.getCurrentMatchId()).orElseThrow(GameOperationException::invalidState);
            if (current != null && (!room.getId().equals(current.getRoomId())
                    || current.getState() == MatchState.MATCH_RESULT)) {
                throw GameOperationException.invalidState();
            }
            // 第11段階は進行中GameMatchだけを解決。終了済みSnapshotの生成は後続段階。
            GameMatch display = room == null || requested == null ? null : matches.findById(requested).orElse(null);
            if (display != null && (!room.getId().equals(display.getRoomId())
                    || display.getState() == MatchState.MATCH_RESULT)) { display = null; }
            Boolean confirmed = null;
            SelectedHandView selected = null;
            if (display != null && current != null && display.getId().equals(current.getId())
                    && display.getState() == MatchState.SELECTING_HAND && user.getState() == UserState.PLAYING) {
                var participant = display.getParticipants().get(user.getId());
                if (participant != null && participant.isActive() && display.getCurrentRound() != null) {
                    var hand = display.getCurrentRound().getSelections().get(user.getId());
                    confirmed = hand != null;
                    if (hand != null) {
                        selected = new SelectedHandView(hand.getType(), hand.getNormalHand(), hand.getOriginalHandId(),
                                RoundJudgeService.handName(hand, display.getOriginalHands()));
                    }
                }
            }
            RoomView roomView = room == null ? null : copyRoom(room);
            StatusView result = new StatusView(now.toString(), user.getState(), room == null ? null : room.getId(),
                    room == null ? "NONE" : current == null ? "WAITING" : "PLAYING",
                    current == null ? null : current.getId(), current == null ? null : current.getState(),
                    roundNumber(current), transitionAt(current), room == null ? null : room.getLastCompletedMatchId(),
                    display == null ? null : display.getId(), display == null ? null : display.getState(),
                    roundNumber(display), transitionAt(display), confirmed, selected, roomView);
            // 全コピー成功後にだけ正常確認とする。読み取りと更新を同じロックで完了する。
            user.setLastSeenAt(now);
            return result;
        }
    }

    private RoomView copyRoom(Room room) {
        return new RoomView(room.getId(), room.getName(), room.getHostUserId(), room.getTargetWins(),
                room.isPreventConsecutiveSameOriginalHand(), room.getMemberIds().stream().map(id -> {
                    GameUser member = users.findById(id).orElseThrow(GameOperationException::invalidState);
                    return new MemberView(id, member.getUsername(), member.getState(), id.equals(room.getHostUserId()));
                }).toList());
    }
    private Integer roundNumber(GameMatch match) {
        return match == null || match.getCurrentRound() == null ? null : match.getCurrentRound().getRoundNumber();
    }
    private String transitionAt(GameMatch match) {
        return match == null || match.getState() != MatchState.ROUND_RESULT || match.getTransitionAt() == null
                ? null : match.getTransitionAt().toString();
    }
    private UUID parseOptionalId(String value) {
        if (value == null) { return null; }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException error) {
            throw GameOperationException.validation("matchId", "対象IDの指定が不正です。");
        }
    }
}
