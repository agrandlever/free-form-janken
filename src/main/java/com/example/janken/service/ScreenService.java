package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.RoundResult;
import com.example.janken.domain.enums.MatchEndType;
import java.util.List;
import java.util.UUID;
import com.example.janken.domain.HandSelection;
import com.example.janken.domain.enums.SelectedHandType;
import com.example.janken.domain.GameMatch;
import com.example.janken.domain.MatchParticipant;
import com.example.janken.domain.OriginalHandSnapshot;
import com.example.janken.domain.enums.MatchState;
import com.example.janken.store.MatchStore;
import com.example.janken.domain.OriginalHand;
import com.example.janken.form.OriginalHandForm;
import com.example.janken.form.OriginalHandDeleteForm;
import com.example.janken.domain.Room;
import com.example.janken.domain.enums.UserState;
import com.example.janken.form.LoginForm;
import com.example.janken.form.RoomActionForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.form.RoomRuleForm;
import com.example.janken.store.GameStateLock;
import com.example.janken.store.RoomStore;
import com.example.janken.store.UserStore;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** アクセス制御と表示用コピー生成を、同じ共有ロック内で行う。 */
@Service
public class ScreenService {
    private final GameStateLock lock;
    private final SessionUserAccess access;
    private final RoomStore rooms;
    private final UserStore users;
    private final MatchStore matches;

    public ScreenService(GameStateLock lock, SessionUserAccess access, RoomStore rooms, UserStore users, MatchStore matches) {
        this.lock = lock;
        this.access = access;
        this.rooms = rooms;
        this.users = users;
        this.matches = matches;
    }

    public record MemberView(String username, boolean host, boolean ready, boolean playing) { }
    public record Screen(String path, String template, Map<String, Object> model) { }

    public Screen current(HttpSession session) {
        synchronized (lock) {
            GameUser user = access.find(session);
            Map<String, Object> model = new LinkedHashMap<>();
            if (user == null) {
                model.put("loginForm", new LoginForm());
                return screen("/", "login", model);
            }
            model.put("username", user.getUsername());
            model.put("userState", user.getState());
            if (user.getState() == UserState.ROOM_NONE) {
                model.put("roomEnterForm", new RoomEnterForm());
                originalHandModel(user, "ROOMS", null, model);
                return screen("/rooms", "rooms", model);
            }
            if (user.getState() == UserState.PLAYING) { return playScreen(user, model); }
            if ((user.getState() != UserState.ROOM_WAITING && user.getState() != UserState.READY) || user.getCurrentRoomId() == null) {
                throw GameOperationException.invalidState();
            }
            Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
            if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
            model.put("roomId", room.getId().toString());
            model.put("roomName", room.getName());
            model.put("selfUserId", user.getId().toString());
            model.put("isHost", user.getId().equals(room.getHostUserId()));
            java.util.List<MemberView> members = room.getMemberIds().stream().map(id -> {
                GameUser member = users.findById(id).orElseThrow(GameOperationException::invalidState);
                return new MemberView(member.getUsername(), id.equals(room.getHostUserId()), member.getState() == UserState.READY, member.getState() == UserState.PLAYING);
            }).toList();
            model.put("members", members);
            model.put("readyMembers", members.stream()
                    .filter(MemberView::ready).toList());
            model.put("playingMembers", members.stream().filter(MemberView::playing).toList());
            model.put("targetWins", room.getTargetWins());
            model.put("preventConsecutiveSameOriginalHand", room.isPreventConsecutiveSameOriginalHand());
            model.put("currentMatchId", room.getCurrentMatchId());
            model.put("matchRunning", room.getCurrentMatchId() != null);
            RoomRuleForm ruleForm = new RoomRuleForm();
            ruleForm.setRoomId(room.getId().toString());
            ruleForm.setTargetWins(Integer.toString(room.getTargetWins()));
            ruleForm.setPreventConsecutiveSameOriginalHand(room.isPreventConsecutiveSameOriginalHand());
            model.put("roomRuleForm", ruleForm);
            RoomActionForm form = new RoomActionForm();
            form.setRoomId(room.getId().toString());
            model.put("roomActionForm", form);
            originalHandModel(user, "ROOM", room.getId().toString(), model);
            return screen("/room", "room", model);
        }
    }

    /** 観戦対象はURLから毎回解決し、ユーザーやSessionへ保存しない。 */
    public Screen gamePage(HttpSession session, String matchId) {
        synchronized (lock) {
            GameUser user = access.find(session);
            // ④・①・未ログインは既存の正規画面を優先する。
            if (user == null || (user.getState() != UserState.ROOM_WAITING && user.getState() != UserState.READY)) {
                return current(session);
            }
            Room room = user.getCurrentRoomId() == null ? null : rooms.findById(user.getCurrentRoomId()).orElse(null);
            if (room == null || !room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
            UUID id = room.getCurrentMatchId();
            if (matchId != null) {
                try {
                    id = UUID.fromString(matchId);
                    if (!id.toString().equalsIgnoreCase(matchId)) { return screen("/room", "", Map.of()); }
                } catch (IllegalArgumentException ex) { return screen("/room", "", Map.of()); }
            }
            GameMatch match = id == null ? null : matches.findById(id).orElse(null);
            if (match == null || !room.getId().equals(match.getRoomId())) { return screen("/room", "", Map.of()); }
            if (match.getState() == MatchState.MATCH_RESULT) {
                return screen("/match-result?matchId=" + id, "", Map.of());
            }
            // 現在Roomの進行中対戦だけを観戦し、active参加者を観戦者として扱わない。
            MatchParticipant participant = match.getParticipants().get(user.getId());
            if (!id.equals(room.getCurrentMatchId()) || (participant != null && participant.isActive())
                    || match.getCurrentRound() == null) { return screen("/room", "", Map.of()); }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("username", user.getUsername());
            model.put("userState", user.getState());
            model.put("roomId", room.getId().toString());
            model.put("matchId", id.toString());
            model.put("roundNumber", match.getCurrentRound().getRoundNumber());
            model.put("targetWins", match.getTargetWins());
            model.put("matchParticipant", false);
            model.put("selectedHand", null);
            model.put("selfHandConfirmed", null);
            model.put("selfSelectedHandName", null);
            model.put("participants", match.getParticipants().values().stream()
                    .map(p -> new ParticipantView(p.getUsername(), p.getScore())).toList());
            model.put("roundHistory", match.getRoundHistory().stream().map(this::historyView).toList());
            // 選択UI用データ・現在selections・OriginalHand相性はコピーしない。
            if (match.getState() == MatchState.ROUND_RESULT) { return roundResultScreen(match, model); }
            return screen("/play?matchId=" + id, "play", model);
        }
    }

    public record ParticipantView(String username, int score) { }

    public record OriginalHandOption(String originalHandId, String name, boolean consecutiveUseRestricted) { }

    private Screen playScreen(GameUser user, Map<String, Object> model) {
        if (user.getCurrentRoomId() == null) { throw GameOperationException.invalidState(); }
        Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
        if (!room.getMemberIds().contains(user.getId()) || room.getCurrentMatchId() == null) {
            throw GameOperationException.invalidState();
        }
        GameMatch match = matches.findById(room.getCurrentMatchId())
                .orElseThrow(GameOperationException::invalidState);
        MatchParticipant participant = match.getParticipants().get(user.getId());
        if (!room.getId().equals(match.getRoomId()) || participant == null || !participant.isActive()
                || (match.getState() != MatchState.SELECTING_HAND && match.getState() != MatchState.ROUND_RESULT)
                || match.getCurrentRound() == null) { throw GameOperationException.invalidState(); }
        model.put("roomId", match.getRoomId().toString());
        model.put("matchId", match.getId().toString());
        model.put("roundNumber", match.getCurrentRound().getRoundNumber());
        model.put("matchParticipant", true);
        model.put("roundHistory", match.getRoundHistory().stream().map(this::historyView).toList());
        RoomActionForm leave = new RoomActionForm();
        leave.setRoomId(room.getId().toString());
        model.put("roomActionForm", leave);
        if (match.getState() == MatchState.ROUND_RESULT) { return roundResultScreen(match, model); }
        model.put("roundHistory", match.getRoundHistory().stream().map(this::historyView).toList());
        model.put("targetWins", match.getTargetWins());
        model.put("preventConsecutiveSameOriginalHand", match.isPreventConsecutiveSameOriginalHand());
        model.put("matchParticipant", true);
        model.put("score", participant.getScore());
        model.put("participants", match.getParticipants().values().stream()
                .map(p -> new ParticipantView(p.getUsername(), p.getScore())).toList());
        // 他人の未公開相性や変更可能なDomainを表示用Modelへ渡さない。
        model.put("originalHandNames", match.getOriginalHands().stream()
                .map(OriginalHandSnapshot::getName).toList());
        // 相性を含まない表示用コピーへ、本人の直前手に基づく制限だけを追加する。
        var options = match.getOriginalHands().stream()
                .map(h -> new OriginalHandOption(h.getHandId().toString(), h.getName(),
                        MatchService.isConsecutiveOriginalHandRestricted(match, participant,
                                new HandSelection(SelectedHandType.ORIGINAL, null, h.getHandId())))).toList();
        model.put("originalHandOptions", options);
        model.put("hasConsecutiveUseRestriction", options.stream().anyMatch(OriginalHandOption::consecutiveUseRestricted));
        // 本人の表示名だけをコピーし、他参加者のselectionや相性は公開しない。
        var self = match.getCurrentRound().getSelections().get(user.getId());
        model.put("selfHandConfirmed", self != null);
        model.put("selfSelectedHandName", self == null ? null : RoundJudgeService.handName(self, match.getOriginalHands()));
        return screen("/play?matchId=" + match.getId(), "play", model);
    }


    public record ResultView(UUID userId, String username, String handName, boolean wonRound) { }
    public record ScoreView(UUID userId, String username, int score) { }
    public record HistoryView(int roundNumber, List<ResultView> results, boolean hasRoundWinner) { }

    private HistoryView historyView(RoundResult result) {
        return new HistoryView(result.getRoundNumber(), result.getEntries().stream()
                .map(e -> new ResultView(e.getUserId(), e.getUsername(), e.getHandName(), e.isWonRound())).toList(),
                result.isHasWinner());
    }

    private Screen roundResultScreen(GameMatch match, Map<String, Object> model) {
        RoundResult result = match.getRoundHistory().stream()
                .filter(r -> r.getRoundNumber() == match.getCurrentRound().getRoundNumber())
                .findFirst().orElseThrow(GameOperationException::invalidState);
        HistoryView copied = historyView(result);
        model.put("results", copied.results());
        // 累積勝数は履歴へ保存せず、現在の参加者から結果の各行に対応するコピーを作る。
        model.put("scores", copied.results().stream().map(e -> {
            MatchParticipant p = match.getParticipants().get(e.userId());
            if (p == null) { throw GameOperationException.invalidState(); }
            return new ScoreView(p.getUserId(), p.getUsername(), p.getScore());
        }).toList());
        model.put("hasRoundWinner", result.isHasWinner());
        model.put("transitionAt", match.getTransitionAt() == null ? null : match.getTransitionAt().toString());
        model.put("matchFinished", match.getPendingEndType() == MatchEndType.NORMAL);
        return screen("/round-result?matchId=" + match.getId(), "round-result", model);
    }

    static void originalHandModel(GameUser user, String returnPage, String roomId, Map<String, Object> model) {
        OriginalHand hand = user.getOriginalHand();
        OriginalHandForm form = new OriginalHandForm();
        form.setReturnPage(returnPage);
        form.setRoomId(roomId);
        if (hand != null) {
            // 表示中にDomainが更新されても変化しない、本人用のコピーを作る。
            form.setName(hand.getName());
            form.setVsRock(hand.getVsRock());
            form.setVsScissors(hand.getVsScissors());
            form.setVsPaper(hand.getVsPaper());
            form.setVsOriginal(hand.getVsOriginal());
        }
        OriginalHandDeleteForm delete = new OriginalHandDeleteForm();
        delete.setReturnPage(returnPage);
        delete.setRoomId(roomId);
        model.put("canEditOriginalHand", user.getState() == UserState.ROOM_NONE || user.getState() == UserState.ROOM_WAITING);
        model.put("hasOriginalHand", hand != null);
        model.put("originalHandName", hand == null ? "" : hand.getName());
        model.put("originalHandForm", form);
        model.put("originalHandDeleteForm", delete);
        model.put("originalHandFormOpen", false);
    }

    private Screen screen(String path, String template, Map<String, Object> model) {
        // Domain参照を含めず、ロック解放後のHTML描画に安全なコピーを返す。
        return new Screen(path, template, Collections.unmodifiableMap(model));
    }
}
