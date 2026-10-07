package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageTenServiceTests {
    GameStateLock lock; UserStore users; RoomStore rooms; MatchStore matches;
    SessionUserAccess access; MatchService service; GameMatch match; Room room;
    List<MockHttpSession> sessions;
    Instant now = Instant.parse("2026-10-07T00:00:00Z");

    @BeforeEach void setup() { fixture(true); }
    void fixture(boolean prevent) {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore(); matches = new MatchStore();
        access = new SessionUserAccess(users);
        service = new MatchService(lock, matches, Clock.fixed(now, ZoneOffset.UTC), rooms, access, new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore()));
        room = new Room(UUID.randomUUID(), "R", UUID.randomUUID()); rooms.save(room);
        match = new GameMatch(UUID.randomUUID(), room.getId(), "R", 3, prevent);
        match.setCurrentRound(new Round(1, now)); room.setCurrentMatchId(match.getId()); matches.save(match);
        sessions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var user = new GameUser(UUID.randomUUID(), "U" + i, now);
            user.setState(UserState.PLAYING); user.setCurrentRoomId(room.getId()); users.save(user);
            room.getMemberIds().add(user.getId());
            var session = new MockHttpSession(); session.setAttribute(SessionUserAccess.USER_ID, user.getId()); sessions.add(session);
            var hand = new OriginalHand(UUID.randomUUID(), "H" + i, HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE);
            user.setOriginalHand(hand);
            match.getParticipants().put(user.getId(), new MatchParticipant(user.getId(), user.getUsername(), hand.getId()));
            match.getOriginalHands().add(new OriginalHandSnapshot(hand.getId(), user.getId(), hand.getName(),
                    hand.getVsRock(), hand.getVsScissors(), hand.getVsPaper(), hand.getVsOriginal()));
        }
    }
    UUID uid(int i) { return access.require(sessions.get(i)).getId(); }
    MatchParticipant self() { return match.getParticipants().get(uid(0)); }
    UUID handId(int i) { return match.getOriginalHands().get(i).getHandId(); }
    HandSelection original(UUID id) { return new HandSelection(SelectedHandType.ORIGINAL, null, id); }
    HandSelection normal(NormalHandType hand) { return new HandSelection(SelectedHandType.NORMAL, hand, null); }
    HandSelectionForm form(HandSelection hand) {
        var form = new HandSelectionForm(); form.setMatchId(match.getId().toString());
        form.setRoundNumber(Integer.toString(match.getCurrentRound().getRoundNumber())); form.setType(hand.getType().name());
        form.setNormalHand(hand.getNormalHand() == null ? null : hand.getNormalHand().name());
        form.setOriginalHandId(hand.getOriginalHandId() == null ? null : hand.getOriginalHandId().toString()); return form;
    }
    void submit(int i, HandSelection hand) { service.submitHand(sessions.get(i), form(hand)); }
    void completeNoWinner(HandSelection hand) {
        submit(0, hand); submit(1, hand);
        assertEquals(MatchState.ROUND_RESULT, match.getState());
        assertFalse(match.getRoundHistory().getLast().isHasWinner());
    }
    void prepareNextRound() {
        // 第11～13段階の遷移処理は作らず、テスト内だけで次ラウンド相当を準備する。
        match.setCurrentRound(new Round(match.getCurrentRound().getRoundNumber() + 1, now));
        match.setState(MatchState.SELECTING_HAND); match.setTransitionAt(null);
    }
    GameOperationException error(int status, Runnable action) {
        var error = assertThrows(GameOperationException.class, action::run);
        assertEquals(status, error.getStatus()); assertEquals(status == 400 ? "VALIDATION_ERROR" : "INVALID_STATE", error.getCode());
        return error;
    }

    @Test void tc044_offAllowsOriginalAcrossCompletedRounds() {
        fixture(false); completeNoWinner(original(handId(0))); prepareNextRound(); submit(0, original(handId(0)));
        assertEquals(handId(0), match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @Test void offAllowsSamePreviousOriginalWithoutReadingIt() {
        fixture(false);
        var participant = spy(self()); match.getParticipants().put(uid(0), participant);
        participant.setPreviousHand(original(handId(0))); submit(0, original(handId(0)));
        verify(participant, never()).getPreviousHand();
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void firstRoundAllowsEveryOriginal(int index) {
        assertNull(self().getPreviousHand()); submit(0, original(handId(index)));
        assertTrue(match.getCurrentRound().getSelections().containsKey(uid(0)));
    }
    @Test void tc046_rejectsSameOriginalAndPreservesAllState() {
        completeNoWinner(original(handId(0))); prepareNextRound();
        // 拒否時に他人の確定済み手や既存の終了予定も変更しないことを確認する。
        submit(1, normal(NormalHandType.ROCK));
        self().setScore(2); match.setPendingEndType(MatchEndType.NORMAL);
        match.getPendingWinnerIds().add(uid(1)); match.setTransitionAt(now.plusSeconds(10));
        var round = match.getCurrentRound(); var selections = new LinkedHashMap<>(round.getSelections());
        var previous = match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();
        var scores = match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        var history = List.copyOf(match.getRoundHistory()); var pending = List.copyOf(match.getPendingWinnerIds());
        var deadline = match.getTransitionAt();
        var e = error(400, () -> submit(0, original(handId(0))));
        assertEquals("前のラウンドと同じオリジナル手は選択できません。", e.getMessage());
        assertEquals(List.of(e.getMessage()), e.getFieldErrors().get("originalHandId"));
        assertSame(round, match.getCurrentRound()); assertEquals(selections, round.getSelections());
        assertEquals(previous, match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList());
        assertEquals(scores, match.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
        assertEquals(history, match.getRoundHistory()); assertEquals(MatchState.SELECTING_HAND, match.getState());
        assertEquals(MatchEndType.NORMAL, match.getPendingEndType()); assertEquals(pending, match.getPendingWinnerIds());
        assertEquals(deadline, match.getTransitionAt()); assertFalse(round.getSelections().containsKey(uid(0)));
    }
    @Test void tc047_allowsDifferentOriginal() {
        completeNoWinner(original(handId(0))); prepareNextRound(); submit(0, original(handId(1)));
        assertEquals(handId(1), match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @ParameterizedTest @EnumSource(NormalHandType.class)
    void tc045_allowsRepeatedNormalAcrossCompletedRounds(NormalHandType hand) {
        completeNoWinner(normal(hand)); prepareNextRound(); submit(0, normal(hand));
        assertEquals(hand, match.getCurrentRound().getSelections().get(uid(0)).getNormalHand());
    }
    @ParameterizedTest @EnumSource(NormalHandType.class)
    void originalToNormalAllowedWithoutReadingPrevious(NormalHandType hand) {
        var participant = spy(self()); match.getParticipants().put(uid(0), participant);
        participant.setPreviousHand(original(handId(0))); submit(0, normal(hand));
        verify(participant, never()).getPreviousHand();
    }
    @ParameterizedTest @EnumSource(NormalHandType.class)
    void normalToOriginalAllowed(NormalHandType hand) {
        self().setPreviousHand(normal(hand)); submit(0, original(handId(0)));
        assertEquals(handId(0), match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @Test void tc107_noWinnerUpdatesPreviousAndNormalResetsRestriction() {
        completeNoWinner(original(handId(0)));
        assertEquals(handId(0), self().getPreviousHand().getOriginalHandId()); assertEquals(0, self().getScore());
        prepareNextRound(); error(400, () -> submit(0, original(handId(0))));
        completeNoWinner(normal(NormalHandType.ROCK));
        assertEquals(SelectedHandType.NORMAL, self().getPreviousHand().getType());
        assertEquals(NormalHandType.ROCK, self().getPreviousHand().getNormalHand());
        prepareNextRound(); completeNoWinner(original(handId(0)));
        assertEquals(handId(0), self().getPreviousHand().getOriginalHandId());
        assertEquals(3, match.getRoundHistory().size());
    }
    @Test void tc107_newMatchInitializesPreviousEvenAfterOriginalLastRound() {
        completeNoWinner(original(handId(0))); self().setScore(2); self().setActive(false);
        var oldMatch = match; var originalId = handId(0);
        room.setPreventConsecutiveSameOriginalHand(true);
        var participants = sessions.stream().map(access::require).toList();
        participants.forEach(u -> u.setState(UserState.READY));
        UUID id;
        synchronized (lock) { id = service.startMatch(room, participants); }
        match = matches.findById(id).orElseThrow();
        assertNotEquals(oldMatch.getId(), id);
        for (var p : match.getParticipants().values()) { assertEquals(0, p.getScore()); assertTrue(p.isActive()); assertNull(p.getPreviousHand()); }
        assertEquals(originalId, oldMatch.getParticipants().get(uid(0)).getPreviousHand().getOriginalHandId());
        submit(0, original(originalId));
        assertEquals(originalId, match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @Test void sameNameDifferentIdsAllowed() {
        var old = match.getOriginalHands().get(1); var a = match.getOriginalHands().getFirst();
        match.getOriginalHands().set(1, new OriginalHandSnapshot(old.getHandId(), old.getOwnerUserId(), a.getName(),
                old.getVsRock(), old.getVsScissors(), old.getVsPaper(), old.getVsOriginal()));
        self().setPreviousHand(original(handId(0))); submit(0, original(handId(1)));
        assertEquals(handId(1), match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @Test void sameIdRejectedDespiteChangedDisplayName() {
        completeNoWinner(original(handId(0))); prepareNextRound();
        var old = match.getOriginalHands().getFirst();
        match.getOriginalHands().set(0, new OriginalHandSnapshot(old.getHandId(), old.getOwnerUserId(), "別の名前",
                old.getVsRock(), old.getVsScissors(), old.getVsPaper(), old.getVsOriginal()));
        error(400, () -> submit(0, original(handId(0)))); assertTrue(match.getCurrentRound().getSelections().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "oldRound"})
    void invalidStateBeforeConsecutiveRestriction(String kind) {
        self().setPreviousHand(original(handId(0))); var form = form(original(handId(0)));
        if (kind.equals("duplicate")) { submit(0, normal(NormalHandType.ROCK)); }
        else { match.setCurrentRound(new Round(2, now)); }
        var selections = new LinkedHashMap<>(match.getCurrentRound().getSelections());
        error(409, () -> service.submitHand(sessions.getFirst(), form));
        assertEquals(selections, match.getCurrentRound().getSelections());
    }
    @Test void unavailableIdValidatedBeforeConsecutiveRestriction() {
        var id = UUID.randomUUID(); self().setPreviousHand(original(id));
        var e = error(400, () -> submit(0, original(id)));
        assertEquals("対戦開始時に固定された手を選択してください。", e.getMessage());
        assertTrue(match.getCurrentRound().getSelections().isEmpty());
    }
    @Test void usesMatchStartRuleRatherThanCurrentRoomRule() {
        room.setPreventConsecutiveSameOriginalHand(false); self().setPreviousHand(original(handId(0)));
        error(400, () -> submit(0, original(handId(0))));
        fixture(false); room.setPreventConsecutiveSameOriginalHand(true); self().setPreviousHand(original(handId(0)));
        submit(0, original(handId(0)));
    }
    @Test void previousLookupAndRegistrationHoldSharedLock() {
        var participant = spy(self()); match.getParticipants().put(uid(0), participant);
        participant.setPreviousHand(original(handId(0)));
        doAnswer(invocation -> { assertTrue(Thread.holdsLock(lock)); return invocation.callRealMethod(); }).when(participant).getPreviousHand();
        error(400, () -> submit(0, original(handId(0)))); submit(0, original(handId(1)));
        verify(participant, times(2)).getPreviousHand();
    }
}
