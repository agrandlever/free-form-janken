package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.MatchResultReturnForm;
import com.example.janken.store.*;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 終了時の保存用コピーと、終了済み結果の権限・表示用コピーを担当する。 */
@Service
public class MatchResultService {
    private final GameStateLock lock;
    private final MatchResultStore results;
    private final SessionUserAccess access;
    private final RoomStore rooms;
    private final MatchStore matches;

    public MatchResultService(GameStateLock lock, MatchResultStore results, SessionUserAccess access,
            RoomStore rooms, MatchStore matches) {
        this.lock = lock; this.results = results; this.access = access; this.rooms = rooms; this.matches = matches;
    }

    /** 終了全体の途中でロックを解放しないため、MatchServiceからだけ呼ぶ。 */
    MatchResultSnapshot saveResult(GameMatch match, Instant finishedAt) {
        if (!Thread.holdsLock(lock)) { throw new IllegalStateException("終了保存にはGameStateLockが必要です。"); }
        var saved = results.findById(match.getId());
        if (saved.isPresent()) { return saved.get(); }
        if (match.getEndType() == null) { throw new IllegalStateException("終了種別が未確定です。"); }
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        // activeや現在GameUserによる絞り込みをせず、開始時の全参加者を保存する。
        match.getParticipants().forEach((id, p) -> { names.put(id, p.getUsername()); scores.put(id, p.getScore()); });
        var snapshot = new MatchResultSnapshot(match.getId(), match.getRoomId(), match.getRoomName(),
                match.getTargetWins(), match.isPreventConsecutiveSameOriginalHand(), names,
                match.getEndType(), match.getWinnerIds(), scores, match.getRoundHistory(),
                match.getOriginalHands(), finishedAt);
        results.save(snapshot);
        return snapshot;
    }

    public record WinnerView(UUID userId, String username) { }
    public record AffinityView(UUID handId, String handName, HandRelation vsRock, HandRelation vsScissors,
            HandRelation vsPaper, HandRelation vsOriginal) { }

    /** 保存済みSnapshot以外を参照せず結果を再現する。 */
    public Map<String, Object> displayModel(MatchResultSnapshot snapshot) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("roomId", snapshot.getRoomId().toString());
        model.put("matchId", snapshot.getMatchId().toString());
        model.put("roomName", snapshot.getRoomName());
        model.put("endType", snapshot.getEndType());
        model.put("winners", snapshot.getWinnerIds().stream()
                .map(id -> new WinnerView(id, snapshot.getParticipantNames().get(id))).toList());
        model.put("finalScores", snapshot.getParticipantNames().entrySet().stream()
                .map(e -> new ScreenService.ScoreView(e.getKey(), e.getValue(), snapshot.getFinalScores().get(e.getKey()))).toList());
        model.put("roundHistory", snapshot.getRoundHistory().stream()
                .map(r -> new ScreenService.HistoryView(r.getRoundNumber(), r.getEntries().stream()
                        .map(e -> new ScreenService.ResultView(e.getUserId(), e.getUsername(), e.getHandName(), e.isWonRound())).toList(),
                        r.isHasWinner())).toList());
        model.put("originalHandAffinities", snapshot.getOriginalHandAffinities().stream()
                .map(h -> new AffinityView(h.getHandId(), h.getName(), h.getVsRock(), h.getVsScissors(), h.getVsPaper(), h.getVsOriginal())).toList());
        var form = new MatchResultReturnForm();
        form.setRoomId(snapshot.getRoomId().toString()); form.setMatchId(snapshot.getMatchId().toString());
        model.put("matchResultReturnForm", form);
        return Collections.unmodifiableMap(model);
    }

    public ScreenService.Screen page(HttpSession session, String matchId) {
        synchronized (lock) {
            GameUser user = access.find(session);
            if (user == null) { return redirect("/"); }
            if (user.getState() == UserState.ROOM_NONE) { return redirect("/rooms"); }
            if (user.getState() == UserState.PLAYING) { return redirect(currentMatchPath(user)); }
            Room room = requireRoom(user);
            UUID id = matchId == null ? room.getLastCompletedMatchId() : parseId(matchId);
            MatchResultSnapshot snapshot = permitted(user, id);
            if (snapshot == null) { return redirect("/room"); }
            String url = "/match-result?matchId=" + snapshot.getMatchId();
            if (matchId == null) { return redirect(url); }
            Map<String, Object> model = new LinkedHashMap<>(displayModel(snapshot));
            model.put("username", user.getUsername()); model.put("userState", user.getState());
            return new ScreenService.Screen(url, "match-result", Collections.unmodifiableMap(model));
        }
    }

    public String returnToRoom(HttpSession session, MatchResultReturnForm form) {
        synchronized (lock) {
            GameUser user = access.require(session);
            // 古いフォームの対象より、別タブで参加した本人の現在対戦を優先する。
            if (user.getState() == UserState.PLAYING) { return currentMatchPath(user); }
            requireRoom(user);
            UUID roomId = parseId(form.getRoomId());
            UUID matchId = parseId(form.getMatchId());
            if (!user.getCurrentRoomId().equals(roomId) || permitted(user, matchId) == null) {
                throw GameOperationException.invalidState();
            }
            return "/room";
        }
    }

    private MatchResultSnapshot permitted(GameUser user, UUID id) {
        if (id == null || (user.getState() != UserState.ROOM_WAITING && user.getState() != UserState.READY)) { return null; }
        return results.findById(id).filter(s -> s.getRoomId().equals(user.getCurrentRoomId())).orElse(null);
    }
    private Room requireRoom(GameUser user) {
        if ((user.getState() != UserState.ROOM_WAITING && user.getState() != UserState.READY) || user.getCurrentRoomId() == null) {
            throw GameOperationException.invalidState();
        }
        Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
        if (!room.getMemberIds().contains(user.getId())) { throw GameOperationException.invalidState(); }
        return room;
    }
    private String currentMatchPath(GameUser user) {
        if (user.getCurrentRoomId() == null) { throw GameOperationException.invalidState(); }
        Room room = rooms.findById(user.getCurrentRoomId()).orElseThrow(GameOperationException::invalidState);
        if (!room.getMemberIds().contains(user.getId()) || room.getCurrentMatchId() == null) { throw GameOperationException.invalidState(); }
        GameMatch match = matches.findById(room.getCurrentMatchId()).orElseThrow(GameOperationException::invalidState);
        var participant = match.getParticipants().get(user.getId());
        if (!room.getId().equals(match.getRoomId()) || participant == null || !participant.isActive()) { throw GameOperationException.invalidState(); }
        return switch (match.getState()) {
            case SELECTING_HAND -> "/play?matchId=" + match.getId();
            case ROUND_RESULT -> "/round-result?matchId=" + match.getId();
            default -> throw GameOperationException.invalidState();
        };
    }
    private UUID parseId(String value) {
        try { UUID id = UUID.fromString(value == null ? "" : value); return id.toString().equalsIgnoreCase(value) ? id : null; }
        catch (IllegalArgumentException ex) { return null; }
    }
    private ScreenService.Screen redirect(String path) { return new ScreenService.Screen(path, "", Map.of()); }
}
