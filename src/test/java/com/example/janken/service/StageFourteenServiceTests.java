package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;

class StageFourteenServiceTests {
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
    List<GameUser> players; List<MockHttpSession> sessions;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore(); matches = new MatchStore();
        results = new MatchResultStore(); clock = new MutableClock(); access = new SessionUserAccess(users);
        resultService = new MatchResultService(lock, results, access, rooms, matches);
        service = new MatchService(lock, matches, clock, rooms, access, new RoundJudgeService(), users, resultService);
        roomService = new RoomService(lock, rooms, access, clock, users, service);
        auth = new AuthService(lock, users, access, roomService, clock);
        status = new StatusService(lock, access, users, rooms, matches, clock, results);
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


    OriginalHandService hands(){return new OriginalHandService(lock,access,rooms,results);}
    void finish(){start(2,1,false);round(true);clock.now=match.getTransitionAt();service.advanceMatch(match.getId());}
    OriginalHandForm form(){var f=new OriginalHandForm();f.setReturnPage("MATCH_RESULT");f.setRoomId(room.getId().toString());f.setResultMatchId(match.getId().toString());f.setName("更新手");f.setVsRock(HandRelation.LOSE);f.setVsScissors(HandRelation.WIN);f.setVsPaper(HandRelation.DRAW);f.setVsOriginal(HandRelation.DRAW);return f;}
    OriginalHandDeleteForm deletion(){var f=new OriginalHandDeleteForm();f.setReturnPage("MATCH_RESULT");f.setRoomId(room.getId().toString());f.setResultMatchId(match.getId().toString());return f;}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sharedLockWaitsAndRechecksReadyState(boolean delete) throws Exception {
        finish();var current=players.getFirst().getOriginalHand();var s=sessions.getFirst();var f=form();var d=deletion();
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<?> future;
            synchronized(lock){var entered=new CountDownLatch(1);future=pool.submit(()->{entered.countDown();var e=assertThrows(GameOperationException.class,()->{if(delete)hands().delete(s,d);else hands().save(s,f);});assertEquals(409,e.getStatus());});
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(100,TimeUnit.MILLISECONDS));roomService.ready(s,room.getId().toString());}
            future.get(5,TimeUnit.SECONDS);
        }
        assertSame(current,players.getFirst().getOriginalHand());assertEquals(UserState.READY,players.getFirst().getState());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void operationFirstThenReadyUsesCurrentSettings(boolean delete){
        finish();var s=sessions.getFirst();var snapshot=saved();
        if(delete){hands().delete(s,deletion());var e=assertThrows(GameOperationException.class,()->roomService.ready(s,room.getId().toString()));assertEquals("ORIGINAL_HAND_REQUIRED",e.getCode());assertNull(players.getFirst().getOriginalHand());}
        else {hands().save(s,form());roomService.ready(s,room.getId().toString());assertEquals("更新手",players.getFirst().getOriginalHand().getName());assertEquals(UserState.READY,players.getFirst().getState());}
        assertSame(snapshot,saved());assertEquals(HandRelation.WIN,snapshot.getOriginalHandAffinities().getFirst().getVsRock());
    }
    @RepeatedTest(20) void saveVersusReadyIsAtomic() throws Exception {race(false);}
    @RepeatedTest(20) void deleteVersusReadyIsAtomic() throws Exception {race(true);}
    void race(boolean delete) throws Exception {
        finish();var s=sessions.getFirst();var before=players.getFirst().getOriginalHand();var f=form();var d=deletion();var snapshot=saved();
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var op=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{if(delete)hands().delete(s,d);else hands().save(s,f);return "OK";}catch(GameOperationException e){assertEquals(409,e.getStatus());return e.getCode();}});
            var ready=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{roomService.ready(s,room.getId().toString());return "OK";}catch(GameOperationException e){assertEquals(409,e.getStatus());return e.getCode();}});
            var operation=op.get(5,TimeUnit.SECONDS);var preparation=ready.get(5,TimeUnit.SECONDS);
            synchronized(lock){
                if(operation.equals("OK")&&delete){assertEquals("ORIGINAL_HAND_REQUIRED",preparation);assertNull(players.getFirst().getOriginalHand());assertEquals(UserState.ROOM_WAITING,players.getFirst().getState());}
                else {assertEquals("OK",preparation);assertEquals(UserState.READY,players.getFirst().getState());if(operation.equals("OK")){assertFalse(delete);assertEquals("更新手",players.getFirst().getOriginalHand().getName());}else {assertEquals("INVALID_STATE",operation);assertSame(before,players.getFirst().getOriginalHand());}}
                assertSame(snapshot,saved());assertEquals(HandRelation.WIN,snapshot.getOriginalHandAffinities().getFirst().getVsRock());
            }
        }
    }
}
