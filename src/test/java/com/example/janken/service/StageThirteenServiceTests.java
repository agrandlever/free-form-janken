package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.*;
import com.example.janken.store.*;
import com.example.janken.scheduler.MatchTransitionScheduler;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;

class StageThirteenServiceTests {
    static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    static class MutableClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    GameStateLock lock; UserStore users; RoomStore rooms; MatchStore matches; MatchResultStore results;
    SessionUserAccess access; MatchService service; MatchResultService resultService; RoomService roomService;
    AuthService auth; StatusService status; MutableClock clock; Room room; GameMatch match;
    MatchTransitionScheduler scheduler;
    List<GameUser> players; List<MockHttpSession> sessions;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore(); matches = new MatchStore();
        results = spy(new MatchResultStore()); clock = new MutableClock(); access = new SessionUserAccess(users);
        resultService = new MatchResultService(lock, results, access, rooms, matches);
        service = new MatchService(lock, matches, clock, rooms, access, new RoundJudgeService(), users, resultService);
        roomService = new RoomService(lock, rooms, access, clock, users, service);
        auth = new AuthService(lock, users, access, roomService, clock);
        status = new StatusService(lock, access, users, rooms, matches, clock, results);
        scheduler = new MatchTransitionScheduler(lock, matches, service, clock);
        players = new ArrayList<>(); sessions = new ArrayList<>();
        room = new Room(UUID.randomUUID(), "R", UUID.randomUUID()); rooms.save(room);
    }
    void start(int count, int target, boolean restriction) {
        room.setTargetWins(target); room.setPreventConsecutiveSameOriginalHand(restriction);
        for (int i = 0; i < count; i++) {
            var u = new GameUser(UUID.randomUUID(), "開始時" + i, START);
            u.setOriginalHand(new OriginalHand(UUID.randomUUID(), "固定手" + i,
                    HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE));
            u.setState(UserState.READY); u.setCurrentRoomId(room.getId()); users.save(u); room.getMemberIds().add(u.getId());
            var s = new MockHttpSession(); s.setAttribute(SessionUserAccess.USER_ID, u.getId());
            players.add(u); sessions.add(s);
        }
        room.setHostUserId(players.getFirst().getId());
        synchronized (lock) { match = matches.findById(service.startMatch(room, players)).orElseThrow(); }
    }
    void submit(int i, NormalHandType hand) {
        var f = new HandSelectionForm(); f.setMatchId(match.getId().toString());
        f.setRoundNumber(Integer.toString(match.getCurrentRound().getRoundNumber())); f.setType("NORMAL"); f.setNormalHand(hand.name());
        service.submitHand(sessions.get(i), f);
    }
    void round(boolean winner) {
        for (int i = 0; i < players.size(); i++) { submit(i, winner && i > 0 ? NormalHandType.SCISSORS : NormalHandType.ROCK); }
    }
    void leave(int i) { roomService.leaveRoom(sessions.get(i), room.getId().toString()); }
    MatchResultSnapshot saved() { return results.findById(match.getId()).orElseThrow(); }
    void assertEnded(MatchEndType type) {
        assertEquals(MatchState.MATCH_RESULT, match.getState()); assertEquals(type, match.getEndType());
        assertEquals(type, saved().getEndType()); assertNull(match.getTransitionAt()); assertNull(room.getCurrentMatchId());
        assertEquals(match.getId(), room.getLastCompletedMatchId()); assertEquals(1, results.findAll().size());
        assertEquals(players.size(), saved().getParticipantNames().size());
        assertEquals(saved().getParticipantNames().keySet(), saved().getFinalScores().keySet());
    }


    // 結果は実際の手確定から生成し、期限の1ms前・1ns前・ちょうど・直後を固定Clockで確認する。
    @ParameterizedTest @CsvSource({"false,-1000000", "false,-1", "false,0", "false,1",
            "true,-1000000", "true,-1", "true,0", "true,1"})
    void undecidedBoundaryPreservesHistoryScoreAndPreviousHand(boolean winner, long nanos) {
        start(3, 3, true); round(winner);
        Instant deadline = START.plusSeconds(winner ? 10 : 5);
        assertEquals(deadline, match.getTransitionAt());
        var oldRound = match.getCurrentRound(); var history = List.copyOf(match.getRoundHistory());
        var score = match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        var previous = match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();
        clock.now = deadline.plusNanos(nanos);
        scheduler.runTransitions(); scheduler.runTransitions(); scheduler.runTransitions();
        if (nanos < 0) {
            assertEquals(MatchState.ROUND_RESULT, match.getState()); assertSame(oldRound, match.getCurrentRound());
            assertEquals(deadline, match.getTransitionAt());
        } else {
            assertEquals(MatchState.SELECTING_HAND, match.getState()); assertEquals(2, match.getCurrentRound().getRoundNumber());
            assertTrue(match.getCurrentRound().getSelections().isEmpty()); assertNull(match.getTransitionAt());
            assertEquals(match.getId(), room.getCurrentMatchId()); assertNull(room.getLastCompletedMatchId());
        }
        assertEquals(history, match.getRoundHistory());
        assertEquals(score, match.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
        assertEquals(previous, match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList());
        assertTrue(results.findAll().isEmpty());
    }

    @ParameterizedTest @ValueSource(longs={-1000000,-1,0,1})
    void tc051NormalBoundaryAndSingleSnapshotSave(long nanos) {
        start(2, 1, false); round(true); var oldRound = match.getCurrentRound();
        assertEquals(START.plusSeconds(10), match.getTransitionAt()); clock.now = match.getTransitionAt().plusNanos(nanos);
        scheduler.runTransitions();
        if (nanos < 0) {
            assertEquals(MatchState.ROUND_RESULT, match.getState()); assertTrue(results.findAll().isEmpty());
            assertTrue(players.stream().allMatch(u -> u.getState() == UserState.PLAYING));
        } else {
            assertEnded(MatchEndType.NORMAL); var snapshot = saved();
            assertEquals(List.of(players.getFirst().getId()), snapshot.getWinnerIds());
            assertEquals(Map.of(players.get(0).getId(), 1, players.get(1).getId(), 0), snapshot.getFinalScores());
            assertTrue(players.stream().allMatch(u -> u.getState() == UserState.ROOM_WAITING));
            var newer = UUID.randomUUID(); room.setLastCompletedMatchId(newer);
            scheduler.runTransitions(); scheduler.runTransitions();
            assertSame(snapshot, saved()); assertEquals(newer, room.getLastCompletedMatchId());
            verify(results, times(1)).save(any(MatchResultSnapshot.class));
        }
        assertSame(oldRound, match.getCurrentRound()); assertEquals(1, match.getRoundHistory().size());
    }

    @ParameterizedTest @EnumSource(MatchState.class)
    void noDeadlineIsNeverDelegated(MatchState state) {
        start(2, 3, false); match.setState(state); match.setTransitionAt(null);
        var delegate = spy(service); var runner = new MatchTransitionScheduler(lock, matches, delegate, clock);
        runner.runTransitions(); runner.runTransitions();
        assertEquals(state, match.getState()); assertEquals(1, match.getCurrentRound().getRoundNumber());
        verify(delegate, never()).advanceMatch(any()); assertTrue(results.findAll().isEmpty());
    }

    @ParameterizedTest @EnumSource(value=MatchState.class, names={"SELECTING_HAND","MATCH_RESULT"})
    void otherStatesWithExpiredDeadlineAreNeverDelegated(MatchState state) {
        start(2, 3, false); match.setState(state); match.setTransitionAt(START);
        var delegate = spy(service); new MatchTransitionScheduler(lock, matches, delegate, clock).runTransitions();
        verify(delegate, never()).advanceMatch(any()); assertEquals(state, match.getState());
        assertEquals(1, match.getCurrentRound().getRoundNumber()); assertEquals(START, match.getTransitionAt());
    }

    @ParameterizedTest @CsvSource({"leave,false", "leave,true", "logout,false", "logout,true"})
    void tc098DeletedRoomStillFinishesAndSameNameRoomIsUntouched(String operation, boolean recreate) {
        start(2, 1, false); round(true); var id = match.getId(); var deadline = match.getTransitionAt();
        var winners = List.copyOf(match.getPendingWinnerIds());
        for (int i=0; i<2; i++) { if (operation.equals("leave")) leave(i); else auth.logout(sessions.get(i)); }
        assertTrue(rooms.findById(room.getId()).isEmpty()); assertSame(match, matches.findById(id).orElseThrow());
        Room replacement = new Room(UUID.randomUUID(), room.getName(), UUID.randomUUID());
        var current = UUID.randomUUID(); var last = UUID.randomUUID();
        replacement.setCurrentMatchId(current); replacement.setLastCompletedMatchId(last); replacement.setTargetWins(9);
        if (recreate) rooms.save(replacement);
        clock.now = deadline.minusNanos(1); scheduler.runTransitions(); assertTrue(results.findAll().isEmpty());
        clock.now = deadline; scheduler.runTransitions(); scheduler.runTransitions();
        assertEquals(MatchState.MATCH_RESULT, match.getState()); assertEquals(MatchEndType.NORMAL, saved().getEndType());
        assertEquals(winners, saved().getWinnerIds()); assertEquals(room.getId(), saved().getRoomId());
        assertEquals(2, saved().getParticipantNames().size()); assertEquals(1, saved().getFinalScores().get(players.getFirst().getId()));
        assertEquals(current, replacement.getCurrentMatchId()); assertEquals(last, replacement.getLastCompletedMatchId());
        assertEquals(9, replacement.getTargetWins()); assertTrue(replacement.getMemberIds().isEmpty());
        verify(results, times(1)).save(any(MatchResultSnapshot.class));
    }

    GameMatch extra(MatchState state, Instant deadline, boolean normal) {
        var m = new GameMatch(UUID.randomUUID(), UUID.randomUUID(), "別Room", 3, false);
        m.setCurrentRound(new Round(7, START)); m.setState(state); m.setTransitionAt(deadline);
        for (int i=0; i<2; i++) {var id=UUID.randomUUID(); m.getParticipants().put(id,new MatchParticipant(id,"P"+i,UUID.randomUUID()));}
        if (normal) {m.setPendingEndType(MatchEndType.NORMAL); m.getPendingWinnerIds().add(m.getParticipants().keySet().iterator().next());}
        matches.save(m); return m;
    }
    @Test void oneScanProcessesMixedMatchesIndependently() {
        var selecting = extra(MatchState.SELECTING_HAND, START, false);
        var before = extra(MatchState.ROUND_RESULT, START.plusNanos(1), false);
        var next = extra(MatchState.ROUND_RESULT, START, false);
        var normal = extra(MatchState.ROUND_RESULT, START, true);
        var ended = extra(MatchState.MATCH_RESULT, START, false);
        var noDeadline = extra(MatchState.ROUND_RESULT, null, false);
        scheduler.runTransitions(); scheduler.runTransitions();
        assertEquals(MatchState.SELECTING_HAND, selecting.getState()); assertEquals(7, selecting.getCurrentRound().getRoundNumber());
        assertEquals(MatchState.ROUND_RESULT, before.getState()); assertEquals(7, before.getCurrentRound().getRoundNumber());
        assertEquals(START.plusNanos(1), before.getTransitionAt());
        assertEquals(MatchState.SELECTING_HAND, next.getState()); assertEquals(8, next.getCurrentRound().getRoundNumber());
        assertEquals(MatchState.MATCH_RESULT, normal.getState()); assertEquals(MatchEndType.NORMAL, normal.getEndType());
        assertEquals(MatchState.MATCH_RESULT, ended.getState()); assertEquals(7, ended.getCurrentRound().getRoundNumber());
        assertEquals(MatchState.ROUND_RESULT, noDeadline.getState()); assertNull(noDeadline.getTransitionAt());
        assertEquals(1, results.findAll().size()); verify(results, times(1)).save(any(MatchResultSnapshot.class));
    }

    @Test void scanAndClockAndDelegationAllHoldSharedLock() {
        start(2, 3, false); round(false); clock.now = match.getTransitionAt();
        var store = spy(matches); var delegate = spy(service); var time = mock(Clock.class);
        doAnswer(call -> {assertTrue(Thread.holdsLock(lock)); return call.callRealMethod();}).when(store).findAll();
        when(time.instant()).thenAnswer(call -> {assertTrue(Thread.holdsLock(lock)); return clock.now;});
        doAnswer(call -> {assertTrue(Thread.holdsLock(lock)); return call.callRealMethod();}).when(delegate).advanceMatch(match.getId());
        new MatchTransitionScheduler(lock, store, delegate, time).runTransitions();
        verify(time, times(1)).instant(); verify(store, times(1)).findAll(); verify(delegate, times(1)).advanceMatch(match.getId());
        assertEquals(2, match.getCurrentRound().getRoundNumber());
    }

    @ParameterizedTest @ValueSource(strings={"next", "normal", "aborted"})
    void delegateRechecksCurrentStateEvenIfCandidateHasChanged(String change) {
        start(2, change.equals("normal") ? 1 : 3, false); round(!change.equals("next"));
        clock.now = match.getTransitionAt(); var delegate = spy(service);
        doAnswer(call -> {
            // 候補確認後の変化を模擬してから、本来のadvanceMatchを実行する。
            if (change.equals("aborted")) service.abortMatch(match.getId()); else service.advanceMatch(match.getId());
            return call.callRealMethod();
        }).when(delegate).advanceMatch(match.getId());
        new MatchTransitionScheduler(lock, matches, delegate, clock).runTransitions();
        if (change.equals("next")) {assertEquals(2, match.getCurrentRound().getRoundNumber()); assertTrue(results.findAll().isEmpty());}
        else {assertEnded(change.equals("normal") ? MatchEndType.NORMAL : MatchEndType.ABORTED); verify(results,times(1)).save(any(MatchResultSnapshot.class));}
        assertEquals(1, match.getRoundHistory().size());
    }

    @Test void serviceExceptionIsPropagatedAndLockIsReleased() {
        start(2,3,false); round(false); clock.now=match.getTransitionAt(); var delegate=spy(service);
        var failure=new IllegalStateException("失敗を隠さない"); doThrow(failure).when(delegate).advanceMatch(match.getId());
        assertSame(failure,assertThrows(IllegalStateException.class,()->new MatchTransitionScheduler(lock,matches,delegate,clock).runTransitions()));
        assertFalse(Thread.holdsLock(lock)); assertEquals(MatchState.ROUND_RESULT,match.getState());
    }

    String outcome(Runnable action) {try{action.run();return "OK";}catch(GameOperationException e){return e.getCode();}}
    // 先行操作を共有ロック内で実行し、競合操作が同じロックで待つことも検証する。
    List<String> ordered(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newSingleThreadExecutor();var ref=new AtomicReference<Thread>();var entered=new CountDownLatch(1);Future<String> future;
        try {String result;synchronized(lock){future=pool.submit(()->{ref.set(Thread.currentThread());entered.countDown();return outcome(second);});
            assertTrue(entered.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(ref.get().getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.onSpinWait();
            assertEquals(Thread.State.BLOCKED,ref.get().getState());result=outcome(first);assertFalse(future.isDone());}
            return List.of(result,future.get(5,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }

    @ParameterizedTest @CsvSource({"leave,true,true", "leave,true,false", "logout,true,true", "logout,true,false",
            "leave,false,true", "leave,false,false", "logout,false,true", "logout,false,false"})
    void schedulerAndDepartureRespectBothLockOrders(String operation, boolean normal, boolean schedulerFirst) throws Exception {
        start(2,normal?1:3,false); round(true); clock.now=match.getTransitionAt();
        Runnable depart=operation.equals("leave")?()->leave(0):()->auth.logout(sessions.getFirst());
        assertEquals(List.of("OK","OK"),ordered(schedulerFirst?scheduler::runTransitions:depart,schedulerFirst?depart:scheduler::runTransitions));
        assertEnded(normal?MatchEndType.NORMAL:MatchEndType.ABORTED);
        assertEquals(UserState.ROOM_NONE,players.getFirst().getState()); assertEquals(UserState.ROOM_WAITING,players.get(1).getState());
        if(normal) assertEquals(List.of(players.getFirst().getId()),saved().getWinnerIds());
        else assertTrue(saved().getWinnerIds().isEmpty());
        verify(results,times(1)).save(any(MatchResultSnapshot.class));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentSchedulersAreIdempotent(boolean normal) throws Exception {
        start(2,normal?1:3,false); round(true); clock.now=match.getTransitionAt();
        assertEquals(List.of("OK","OK"),ordered(scheduler::runTransitions,scheduler::runTransitions));
        if(normal) {assertEnded(MatchEndType.NORMAL);verify(results,times(1)).save(any(MatchResultSnapshot.class));}
        else {assertEquals(2,match.getCurrentRound().getRoundNumber());assertTrue(results.findAll().isEmpty());}
        assertEquals(1,match.getRoundHistory().size()); assertEquals(1,match.getParticipants().get(players.getFirst().getId()).getScore());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldRoundPostIsRejectedBeforeOrAfterScheduler(boolean schedulerFirst) throws Exception {
        start(2,3,false); round(false); clock.now=match.getTransitionAt();
        var old=new HandSelectionForm();old.setMatchId(match.getId().toString());old.setRoundNumber("1");old.setType("NORMAL");old.setNormalHand("ROCK");
        Runnable post=()->{assertEquals(409,assertThrows(GameOperationException.class,()->service.submitHand(sessions.getFirst(),old)).getStatus());};
        assertEquals(List.of("OK","OK"),ordered(schedulerFirst?scheduler::runTransitions:post,schedulerFirst?post:scheduler::runTransitions));
        assertEquals(2,match.getCurrentRound().getRoundNumber()); assertTrue(match.getCurrentRound().getSelections().isEmpty());
        assertEquals(1,match.getRoundHistory().size());
    }
}
