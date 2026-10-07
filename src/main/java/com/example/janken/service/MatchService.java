package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.store.RoomStore;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
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
    private final RoomStore rooms;
    private final SessionUserAccess access;
    private final RoundJudgeService judge;
    public MatchService(GameStateLock lock, MatchStore matches, Clock clock, RoomStore rooms,
            SessionUserAccess access, RoundJudgeService judge) {
        this.lock = lock;
        this.matches = matches;
        this.clock = clock;
        this.rooms = rooms;
        this.access = access;
        this.judge = judge;
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

    public UUID submitHand(HttpSession session, HandSelectionForm form) {
        synchronized (lock) {
            GameUser user = access.require(session);
            if (user.getState() != UserState.PLAYING) { throw GameOperationException.invalidState(); }
            UUID matchId = parseId(form.getMatchId(), "matchId");
            int roundNumber;
            try {
                if (form.getRoundNumber() == null || !form.getRoundNumber().matches("[+-]?[0-9]+")) { throw new NumberFormatException(); }
                roundNumber = Integer.parseInt(form.getRoundNumber());
                if (roundNumber < 1) { throw new NumberFormatException(); }
            } catch (NumberFormatException ex) {
                throw GameOperationException.validation("roundNumber", "ラウンド番号は1以上の整数で指定してください。");
            }
            if (user.getCurrentRoomId() == null) { throw GameOperationException.invalidState(); }
            Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
            if (!matchId.equals(room.getCurrentMatchId())) { throw GameOperationException.invalidState(); }
            GameMatch match = matches.findById(matchId).orElseThrow(GameOperationException::invalidState);
            if (!matchId.equals(match.getId())) { throw GameOperationException.invalidState(); }
            MatchParticipant participant = match.getParticipants().get(user.getId());
            if (participant == null || !participant.isActive()) { throw GameOperationException.invalidState(); }
            Round round = match.getCurrentRound();
            if (round == null || roundNumber != round.getRoundNumber()) { throw GameOperationException.invalidState(); }
            if (match.getState() != MatchState.SELECTING_HAND) { throw GameOperationException.invalidState(); }
            // 値が不正な再送でも、確定済みを409で拒否して既存の手を守る。
            if (round.getSelections().containsKey(user.getId())) { throw GameOperationException.invalidState(); }
            HandSelection selection = validateHand(form, match);
            // 使用可能手の確認後、登録前に判定し、違反時は共有状態を一切更新しない。
            if (isConsecutiveOriginalHandRestricted(match, participant, selection)) {
                throw GameOperationException.validation("originalHandId", "前のラウンドと同じオリジナル手は選択できません。");
            }
            round.getSelections().put(user.getId(), copy(selection));
            if (match.getParticipants().values().stream().filter(MatchParticipant::isActive)
                    .allMatch(p -> round.getSelections().containsKey(p.getUserId()))) {
                completeRound(match);
            }
            return matchId;
        }
    }

    /** サーバー検証と表示用判定で共用する。呼び出し元は共有GameStateLockを保持する。 */
    static boolean isConsecutiveOriginalHandRestricted(GameMatch match, MatchParticipant participant,
            HandSelection selection) {
        // OFFと通常手ではpreviousHandを参照せず、そのまま許可する。
        if (!match.isPreventConsecutiveSameOriginalHand() || selection.getType() != SelectedHandType.ORIGINAL) {
            return false;
        }
        HandSelection previous = participant.getPreviousHand();
        return previous != null && previous.getType() == SelectedHandType.ORIGINAL
                && selection.getOriginalHandId().equals(previous.getOriginalHandId());
    }

    private HandSelection validateHand(HandSelectionForm form, GameMatch match) {
        if ("NORMAL".equals(form.getType())) {
            if (!empty(form.getOriginalHandId())) { throw GameOperationException.validation("originalHandId", "通常手にはオリジナル手を指定できません。"); }
            try {
                return new HandSelection(SelectedHandType.NORMAL, NormalHandType.valueOf(form.getNormalHand() == null ? "" : form.getNormalHand()), null);
            } catch (IllegalArgumentException ex) { throw GameOperationException.validation("normalHand", "グー・チョキ・パーから選択してください。"); }
        }
        if ("ORIGINAL".equals(form.getType())) {
            if (!empty(form.getNormalHand())) { throw GameOperationException.validation("normalHand", "オリジナル手には通常手を指定できません。"); }
            UUID id = parseId(form.getOriginalHandId(), "originalHandId");
            if (match.getOriginalHands().stream().noneMatch(h -> h.getHandId().equals(id))) {
                throw GameOperationException.validation("originalHandId", "対戦開始時に固定された手を選択してください。");
            }
            return new HandSelection(SelectedHandType.ORIGINAL, null, id);
        }
        throw GameOperationException.validation("type", "手の種類が不正です。");
    }
    private boolean empty(String value) { return value == null || value.isEmpty(); }
    private UUID parseId(String value, String field) {
        try {
            UUID id = UUID.fromString(value == null ? "" : value);
            if (!id.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException ex) { throw GameOperationException.validation(field, "対象IDの指定が不正です。"); }
    }
    private HandSelection copy(HandSelection hand) {
        return new HandSelection(hand.getType(), hand.getNormalHand(), hand.getOriginalHandId());
    }

    public void completeRound(GameMatch match) {
        synchronized (lock) {
            if (match.getState() != MatchState.SELECTING_HAND) { return; }
            List<MatchParticipant> active = match.getParticipants().values().stream().filter(MatchParticipant::isActive).toList();
            Round round = match.getCurrentRound();
            if (round == null || active.size() < 2 || active.stream().anyMatch(p -> !round.getSelections().containsKey(p.getUserId()))) { return; }
            // 確定時刻は1回だけ読み、結果と遷移期限で共用する。
            Instant decidedAt = clock.instant();
            RoundResult result = judge.judgeRound(round.getRoundNumber(), active, round.getSelections(), match.getOriginalHands(), decidedAt);
            result.getEntries().stream().filter(RoundResultEntry::isWonRound).forEach(e -> {
                MatchParticipant p = match.getParticipants().get(e.getUserId());
                p.setScore(p.getScore() + 1);
            });
            active.forEach(p -> p.setPreviousHand(copy(round.getSelections().get(p.getUserId()))));
            match.getRoundHistory().add(result);
            List<UUID> winners = active.stream().filter(p -> p.getScore() >= match.getTargetWins()).map(MatchParticipant::getUserId).toList();
            match.setPendingEndType(winners.isEmpty() ? null : MatchEndType.NORMAL);
            match.getPendingWinnerIds().clear();
            match.getPendingWinnerIds().addAll(winners);
            match.setTransitionAt(decidedAt.plusSeconds(result.isHasWinner() || !winners.isEmpty() ? 10 : 5));
            match.setState(MatchState.ROUND_RESULT);
        }
    }
}
