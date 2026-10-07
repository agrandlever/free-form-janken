package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageElevenServiceTests {
    GameStateLock lock; UserStore users; RoomStore rooms; MatchStore matches;
    SessionUserAccess access; StatusService status; ScreenService screens;
    GameUser self, other; Room room; GameMatch match; MockHttpSession session;
    Instant now = Instant.parse("2026-10-07T00:00:00Z");

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore(); matches = new MatchStore();
        access = new SessionUserAccess(users);
        status = new StatusService(lock, access, users, rooms, matches, Clock.fixed(now, ZoneOffset.UTC), new MatchResultStore());
        screens = new ScreenService(lock, access, rooms, users, matches);
        self = new GameUser(UUID.randomUUID(), "Alice", now.minusSeconds(30));
        other = new GameUser(UUID.randomUUID(), "Bob", now.minusSeconds(30));
        users.save(self); users.save(other);
        session = new MockHttpSession(); session.setAttribute(SessionUserAccess.USER_ID, self.getId());
        room = new Room(UUID.randomUUID(), "R", self.getId()); rooms.save(room);
    }
    void join(UserState state) {
        self.setCurrentRoomId(room.getId()); self.setState(state); room.getMemberIds().add(self.getId());
        other.setCurrentRoomId(room.getId()); other.setState(UserState.READY); room.getMemberIds().add(other.getId());
    }
    void playing() {
        join(UserState.PLAYING); other.setState(UserState.PLAYING);
        match = new GameMatch(UUID.randomUUID(), room.getId(), "R", 3, true);
        match.setCurrentRound(new Round(1, now));
        for (var user : List.of(self, other)) {
            var hand = new OriginalHand(UUID.randomUUID(), "固定" + user.getUsername(),
                    HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE);
            user.setOriginalHand(hand);
            match.getParticipants().put(user.getId(), new MatchParticipant(user.getId(), user.getUsername(), hand.getId()));
            match.getOriginalHands().add(new OriginalHandSnapshot(hand.getId(), user.getId(), hand.getName(),
                    hand.getVsRock(), hand.getVsScissors(), hand.getVsPaper(), hand.getVsOriginal()));
        }
        matches.save(match); room.setCurrentMatchId(match.getId());
    }
    StatusService.StatusView poll() { return status.status(session, match.getId().toString()); }
    void result(boolean winner, boolean finished) {
        playing();
        match.getRoundHistory().add(new RoundResult(1, List.of(
                new RoundResultEntry(self.getId(), "開始時Alice", "グー", winner),
                new RoundResultEntry(other.getId(), "開始時Bob", "チョキ", winner)), winner, now));
        match.getParticipants().get(self.getId()).setScore(2);
        match.getParticipants().get(other.getId()).setScore(1);
        match.setState(MatchState.ROUND_RESULT);
        match.setTransitionAt(now.plusSeconds(winner || finished ? 10 : 5));
        match.setPendingEndType(finished ? MatchEndType.NORMAL : null);
    }
    @Test void anonymous401() {
        var error = assertThrows(GameOperationException.class, () -> status.status(null, null));
        assertEquals(401, error.getStatus()); assertEquals("LOGIN_REQUIRED", error.getCode());
    }
    @Test void roomNoneAllNullAndTime() {
        var s = status.status(session, UUID.randomUUID().toString());
        assertEquals(now.toString(), s.serverTime()); assertEquals(UserState.ROOM_NONE, s.userState());
        assertEquals("NONE", s.roomState());
        for (Object value : new Object[]{s.currentRoomId(), s.currentMatchId(), s.matchState(), s.roundNumber(),
                s.transitionAt(), s.lastCompletedMatchId(), s.displayMatchId(), s.displayMatchState(),
                s.displayRoundNumber(), s.displayTransitionAt(), s.selfHandConfirmed(), s.selfSelectedHand(), s.room()}) {
            assertNull(value);
        }
        assertEquals(now, self.getLastSeenAt());
    }
    @ParameterizedTest @EnumSource(value=UserState.class, names={"ROOM_WAITING","READY"})
    void waitingAndReadyRoom(UserState state) {
        join(state); room.setTargetWins(5); room.setPreventConsecutiveSameOriginalHand(true);
        var completed = UUID.randomUUID(); room.setLastCompletedMatchId(completed);
        var s = status.status(session, null);
        assertEquals(state, s.userState()); assertEquals("WAITING", s.roomState());
        assertEquals(room.getId(), s.currentRoomId()); assertEquals(completed, s.lastCompletedMatchId());
        assertNull(s.currentMatchId()); assertNull(s.matchState()); assertNull(s.roundNumber()); assertNull(s.transitionAt());
        assertEquals(room.getId(), s.room().id()); assertEquals("R", s.room().name());
        assertEquals(self.getId(), s.room().hostUserId()); assertEquals(5, s.room().targetWins());
        assertTrue(s.room().preventConsecutiveSameOriginalHand());
        assertEquals(List.of(self.getId(),other.getId()), s.room().members().stream().map(StatusService.MemberView::userId).toList());
        assertTrue(s.room().members().getFirst().isHost()); assertFalse(s.room().members().getLast().isHost());
        assertEquals(state, s.room().members().getFirst().userState());
    }
    @Test void selectingUnconfirmed() {
        playing(); match.setTransitionAt(now.plusSeconds(10)); // SELECTINGでは期限を公開しない。
        var s = poll();
        assertEquals("PLAYING", s.roomState()); assertEquals(match.getId(), s.currentMatchId());
        assertEquals(match.getId(), s.displayMatchId()); assertEquals(MatchState.SELECTING_HAND, s.matchState());
        assertEquals(s.matchState(), s.displayMatchState()); assertEquals(1, s.roundNumber());
        assertEquals(1, s.displayRoundNumber()); assertNull(s.transitionAt()); assertNull(s.displayTransitionAt());
        assertEquals(false, s.selfHandConfirmed()); assertNull(s.selfSelectedHand());
    }
    @ParameterizedTest @EnumSource(NormalHandType.class)
    void normalConfirmed(NormalHandType hand) {
        playing(); match.getCurrentRound().getSelections().put(self.getId(), new HandSelection(SelectedHandType.NORMAL,hand,null));
        var s = poll();
        assertEquals(true, s.selfHandConfirmed()); assertEquals(SelectedHandType.NORMAL, s.selfSelectedHand().type());
        assertEquals(hand, s.selfSelectedHand().normalHand()); assertNull(s.selfSelectedHand().originalHandId());
        assertEquals(switch(hand) { case ROCK -> "グー"; case SCISSORS -> "チョキ"; case PAPER -> "パー"; }, s.selfSelectedHand().handName());
    }
    @Test void originalUsesFixedNameAndCopiedValues() {
        playing(); var hand=match.getOriginalHands().getLast();
        match.getCurrentRound().getSelections().put(self.getId(),new HandSelection(SelectedHandType.ORIGINAL,null,hand.getHandId()));
        other.getOriginalHand().setName("変更後"); var s=poll();
        assertEquals(true,s.selfHandConfirmed()); assertEquals("固定Bob",s.selfSelectedHand().handName());
        assertEquals(SelectedHandType.ORIGINAL,s.selfSelectedHand().type()); assertNull(s.selfSelectedHand().normalHand());
        assertEquals(hand.getHandId(),s.selfSelectedHand().originalHandId());
        match.getCurrentRound().getSelections().clear(); assertEquals("固定Bob",s.selfSelectedHand().handName());
    }
    @ParameterizedTest @ValueSource(strings={"none","unknown","otherRoom"})
    void noSelfOrDisplayOutsideTarget(String kind) {
        playing(); String id=null;
        if(kind.equals("unknown")) id=UUID.randomUUID().toString();
        if(kind.equals("otherRoom")) {
            var foreign=new GameMatch(UUID.randomUUID(),UUID.randomUUID(),"秘密",3,false);
            foreign.setCurrentRound(new Round(99,now)); matches.save(foreign); id=foreign.getId().toString();
        }
        var s=status.status(session,id);
        assertEquals(match.getId(),s.currentMatchId());
        assertNull(s.displayMatchId()); assertNull(s.displayMatchState()); assertNull(s.displayRoundNumber());
        assertNull(s.displayTransitionAt()); assertNull(s.selfHandConfirmed()); assertNull(s.selfSelectedHand());
    }
    @ParameterizedTest @ValueSource(strings={"","bad","1-1-1-1-1","00000000-0000-0000-0000-000000000000X"})
    void malformedDoesNotUpdateState(String id) {
        playing(); var seen=self.getLastSeenAt(); var round=match.getCurrentRound();
        var e=assertThrows(GameOperationException.class,()->status.status(session,id));
        assertEquals(400,e.getStatus()); assertEquals("VALIDATION_ERROR",e.getCode());
        assertEquals(seen,self.getLastSeenAt()); assertSame(round,match.getCurrentRound());
        assertTrue(round.getSelections().isEmpty()); assertEquals(UserState.PLAYING,self.getState());
    }
    @ParameterizedTest @ValueSource(strings={"inactive","waiting","ready"})
    void selfConditionsRequired(String condition) {
        playing();
        if(condition.equals("inactive")) match.getParticipants().get(self.getId()).setActive(false);
        else self.setState(condition.equals("waiting")?UserState.ROOM_WAITING:UserState.READY);
        var s=poll(); assertNull(s.selfHandConfirmed()); assertNull(s.selfSelectedHand());
    }
    @Test void roundResultStateAndDeadlineSelfNull() {
        result(true,false); var s=poll();
        assertEquals(MatchState.ROUND_RESULT,s.matchState()); assertEquals(MatchState.ROUND_RESULT,s.displayMatchState());
        assertEquals(now.plusSeconds(10).toString(),s.transitionAt()); assertEquals(s.transitionAt(),s.displayTransitionAt());
        assertNull(s.selfHandConfirmed()); assertNull(s.selfSelectedHand());
    }
    @Test void copiesRoomBeforeLockRelease() {
        join(UserState.READY);var s=status.status(session,null);
        users.save(new GameUser(self.getId(),"別名",now));room.getMemberIds().clear();room.setTargetWins(9);
        assertEquals("Alice",s.room().members().getFirst().username());assertEquals(2,s.room().members().size());
        assertEquals(3,s.room().targetWins());assertThrows(UnsupportedOperationException.class,()->s.room().members().clear());
    }
    @Test void allReadsAndHeartbeatUseSharedLock() {
        playing();
        var guardedUsers=spy(users);var guardedRooms=spy(rooms);var guardedMatches=spy(matches);
        var guardedClock=mock(Clock.class);
        when(guardedClock.instant()).thenAnswer(i->{assertTrue(Thread.holdsLock(lock));return now;});
        doAnswer(i->{assertTrue(Thread.holdsLock(lock));return i.callRealMethod();}).when(guardedUsers).findById(any());
        doAnswer(i->{assertTrue(Thread.holdsLock(lock));return i.callRealMethod();}).when(guardedRooms).findById(any());
        doAnswer(i->{assertTrue(Thread.holdsLock(lock));return i.callRealMethod();}).when(guardedMatches).findById(any());
        var guardedSelf=spy(self);users.save(guardedSelf);
        doAnswer(i->{assertTrue(Thread.holdsLock(lock));return i.callRealMethod();}).when(guardedSelf).setLastSeenAt(any());
        var service=new StatusService(lock,new SessionUserAccess(guardedUsers),guardedUsers,guardedRooms,guardedMatches,guardedClock, new MatchResultStore());
        assertEquals(match.getId(),service.status(session,match.getId().toString()).displayMatchId());
    }
    @ParameterizedTest @CsvSource({"true,false,10","false,false,5","true,true,10"})
    void resultModelCopiesSeparateScoresAndFinalPending(boolean winner,boolean finished,int seconds) {
        result(winner,finished);var screen=screens.current(session);var model=screen.model();
        assertEquals("round-result",screen.template());assertEquals("/round-result?matchId="+match.getId(),screen.path());
        assertEquals(room.getId().toString(),model.get("roomId"));assertEquals(match.getId().toString(),model.get("matchId"));
        assertEquals(1,model.get("roundNumber"));assertEquals(UserState.PLAYING,model.get("userState"));
        assertEquals(winner,model.get("hasRoundWinner"));assertEquals(finished,model.get("matchFinished"));
        assertEquals(now.plusSeconds(seconds).toString(),model.get("transitionAt"));
        var results=(List<ScreenService.ResultView>)model.get("results");
        var scores=(List<ScreenService.ScoreView>)model.get("scores");
        assertEquals(List.of("開始時Alice","開始時Bob"),results.stream().map(ScreenService.ResultView::username).toList());
        assertEquals(List.of("グー","チョキ"),results.stream().map(ScreenService.ResultView::handName).toList());
        assertEquals(List.of(winner,winner),results.stream().map(ScreenService.ResultView::wonRound).toList());
        assertEquals(results.stream().map(ScreenService.ResultView::userId).toList(),scores.stream().map(ScreenService.ScoreView::userId).toList());
        assertEquals(List.of(2,1),scores.stream().map(ScreenService.ScoreView::score).toList());
        match.getParticipants().get(self.getId()).setScore(99);match.getRoundHistory().clear();
        assertEquals(2,scores.getFirst().score());assertEquals(2,results.size());
        assertEquals(List.of("userId","username","handName","wonRound"),
                Arrays.stream(ScreenService.ResultView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        assertThrows(UnsupportedOperationException.class,()->results.clear());
    }
    @Test void pendingNormalIsDisplayFlagNotFinishedState() {
        result(true,true);screens.current(session);poll();
        assertEquals(MatchState.ROUND_RESULT,match.getState());assertEquals(UserState.PLAYING,self.getState());
        assertEquals(MatchEndType.NORMAL,match.getPendingEndType());assertNull(match.getEndType());
    }
    @Test void historyCopiesOnlyCompletedRoundsAndNoScoresOrAffinities() {
        result(true,false);
        match.getRoundHistory().add(new RoundResult(2,List.of(new RoundResultEntry(self.getId(),"Alice","パー",false)),false,now));
        match.setCurrentRound(new Round(3,now));match.setState(MatchState.SELECTING_HAND);match.setTransitionAt(null);
        var history=(List<ScreenService.HistoryView>)screens.current(session).model().get("roundHistory");
        assertEquals(List.of(1,2),history.stream().map(ScreenService.HistoryView::roundNumber).toList());
        assertTrue(history.getFirst().hasRoundWinner());assertFalse(history.getLast().hasRoundWinner());
        assertEquals(2,history.getFirst().results().stream().filter(ScreenService.ResultView::wonRound).count());
        assertEquals("パー",history.getLast().results().getFirst().handName());
        match.getRoundHistory().clear();assertEquals(2,history.size());
        assertThrows(UnsupportedOperationException.class,()->history.clear());
        assertEquals(List.of("roundNumber","results","hasRoundWinner"),
                Arrays.stream(ScreenService.HistoryView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }
}
