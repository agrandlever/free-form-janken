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

class StageTwelveServiceTests {
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

    @ParameterizedTest @CsvSource({"false,-1", "false,0", "false,1", "true,-1", "true,0", "true,1"})
    void advanceBoundaryAndPreservedValues(boolean winner, long nanos) {
        start(3, 3, true); round(winner);
        var oldRound = match.getCurrentRound(); var history = List.copyOf(match.getRoundHistory());
        var score = match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        var previous = match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();
        Instant deadline = match.getTransitionAt(); clock.now = deadline.plusNanos(nanos);
        service.advanceMatch(match.getId());
        if (nanos < 0) { assertSame(oldRound, match.getCurrentRound()); assertEquals(MatchState.ROUND_RESULT, match.getState()); assertEquals(deadline, match.getTransitionAt()); }
        else {
            assertEquals(2, match.getCurrentRound().getRoundNumber()); assertEquals(clock.now, match.getCurrentRound().getStartedAt());
            assertTrue(match.getCurrentRound().getSelections().isEmpty()); assertEquals(MatchState.SELECTING_HAND, match.getState());
            assertNull(match.getTransitionAt()); assertNull(match.getPendingEndType()); assertTrue(match.getPendingWinnerIds().isEmpty());
            var next = match.getCurrentRound(); service.advanceMatch(match.getId()); assertSame(next, match.getCurrentRound());
        }
        assertEquals(score, match.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
        assertEquals(previous, match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList());
        assertEquals(history, match.getRoundHistory()); assertEquals(3, match.getParticipants().size());
        assertEquals(3, match.getOriginalHands().size()); assertEquals(3, match.getTargetWins()); assertTrue(match.isPreventConsecutiveSameOriginalHand());
        assertTrue(results.findAll().isEmpty());
    }
    @ParameterizedTest @EnumSource(MatchState.class)
    void advanceOnlyRoundResultWithDeadline(MatchState state) {
        start(2,3,false); match.setState(state); match.setTransitionAt(null); service.advanceMatch(match.getId());
        assertEquals(state,match.getState()); assertEquals(1,match.getCurrentRound().getRoundNumber()); assertTrue(results.findAll().isEmpty());
        if (state != MatchState.ROUND_RESULT) { match.setTransitionAt(START); service.advanceMatch(match.getId()); assertEquals(state,match.getState()); }
    }
    @ParameterizedTest @CsvSource({"finish,-1000000", "finish,-1", "finish,0", "finish,1", "advance,-1", "advance,0", "advance,1000000"})
    void normalTenSecondBoundary(String operation,long nanos) {
        start(2,1,false); round(true); assertEquals(START.plusSeconds(10),match.getTransitionAt());
        var round=match.getCurrentRound(); clock.now=START.plusSeconds(10).plusNanos(nanos);
        if(operation.equals("finish")) service.finishNormal(match.getId()); else service.advanceMatch(match.getId());
        if(nanos<0) { assertTrue(results.findAll().isEmpty());assertEquals(MatchState.ROUND_RESULT,match.getState());assertEquals(UserState.PLAYING,players.getFirst().getState()); }
        else {
            assertEnded(MatchEndType.NORMAL); assertEquals(match.getPendingWinnerIds(),saved().getWinnerIds()); assertEquals(clock.now,saved().getFinishedAt());
            assertEquals(UserState.ROOM_WAITING,players.getFirst().getState());
            var snapshot=saved(); clock.now=clock.now.plusSeconds(10);service.finishNormal(match.getId());service.advanceMatch(match.getId());service.abortMatch(match.getId());
            assertSame(snapshot,saved());assertEquals(match.getId(),room.getLastCompletedMatchId());
        }
        assertSame(round,match.getCurrentRound());assertEquals(1,match.getRoundHistory().size());assertEquals(1,match.getParticipants().get(players.getFirst().getId()).getScore());
    }
    @ParameterizedTest @ValueSource(strings={"selecting","noPending","noDeadline"})
    void finishNormalRejectsIncompleteConditions(String mode) {
        start(2,1,false);
        if(!mode.equals("selecting")) { round(true); if(mode.equals("noPending"))match.setPendingEndType(null);else match.setTransitionAt(null); }
        clock.now=START.plusSeconds(20); service.finishNormal(match.getId());assertTrue(results.findAll().isEmpty());
    }
    @Test void multipleWinnersAreCopied() {
        start(3,1,false);submit(0,NormalHandType.ROCK);submit(1,NormalHandType.ROCK);submit(2,NormalHandType.SCISSORS);
        assertEquals(2,match.getPendingWinnerIds().size());clock.now=match.getTransitionAt();service.advanceMatch(match.getId());
        assertEnded(MatchEndType.NORMAL);assertEquals(2,saved().getWinnerIds().size());
        match.getPendingWinnerIds().clear();match.getWinnerIds().clear();assertEquals(2,saved().getWinnerIds().size());
        assertEquals(2,((List<?>)resultService.displayModel(saved()).get("winners")).size());
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void advanceAbortsWithAtMostOneActive(int remaining) {
        start(3,3,false);round(true);match.getParticipants().values().stream().skip(remaining).forEach(p->p.setActive(false));
        clock.now=match.getTransitionAt();service.advanceMatch(match.getId());assertEnded(MatchEndType.ABORTED);assertTrue(saved().getWinnerIds().isEmpty());
        assertEquals(1,saved().getRoundHistory().size());assertEquals(1,saved().getFinalScores().get(players.getFirst().getId()));
    }
    @Test void nextRoundStillRestrictsPreviousOriginalHandAndKeepsDepartedCreatorsHand() {
        start(3,3,true);var id=match.getOriginalHands().get(2).getHandId();
        for(int i=0;i<3;i++){var f=new HandSelectionForm();f.setMatchId(match.getId().toString());f.setRoundNumber("1");f.setType("ORIGINAL");f.setOriginalHandId(id.toString());service.submitHand(sessions.get(i),f);}
        leave(2);clock.now=match.getTransitionAt();service.advanceMatch(match.getId());
        var f=new HandSelectionForm();f.setMatchId(match.getId().toString());f.setRoundNumber("2");f.setType("ORIGINAL");f.setOriginalHandId(id.toString());
        assertEquals(400,assertThrows(GameOperationException.class,()->service.submitHand(sessions.getFirst(),f)).getStatus());
        assertEquals(3,match.getOriginalHands().size());assertFalse(match.getParticipants().get(players.get(2).getId()).isActive());
        submit(0,NormalHandType.ROCK);submit(1,NormalHandType.PAPER);assertEquals(MatchState.ROUND_RESULT,match.getState());
        assertEquals(2,match.getRoundHistory().getLast().getEntries().size());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void selectingLeaveRemovesSubmittedSelectionAndRetainsRecord(boolean submitted) {
        start(3,3,false);var p=match.getParticipants().get(players.get(2).getId());p.setScore(2);
        var previous=new HandSelection(SelectedHandType.NORMAL,NormalHandType.PAPER,null);p.setPreviousHand(previous);
        submit(0,NormalHandType.ROCK);if(submitted)submit(2,NormalHandType.PAPER);leave(2);
        assertEquals(MatchState.SELECTING_HAND,match.getState());assertFalse(match.getCurrentRound().getSelections().containsKey(p.getUserId()));
        assertSame(p,match.getParticipants().get(p.getUserId()));assertEquals(2,p.getScore());assertSame(previous,p.getPreviousHand());
        submit(1,NormalHandType.SCISSORS);assertEquals(1,match.getRoundHistory().size());assertEquals(2,match.getRoundHistory().getFirst().getEntries().size());
    }
    @Test void leaveCompletesRoundOnceWhenRemainingSelectionsReady() {
        start(3,3,false);submit(0,NormalHandType.ROCK);submit(1,NormalHandType.SCISSORS);leave(2);
        assertEquals(MatchState.ROUND_RESULT,match.getState());assertEquals(1,match.getRoundHistory().size());
        assertEquals(1,match.getParticipants().get(players.getFirst().getId()).getScore());
        service.handleParticipantLeave(match.getId(),players.get(2).getId());service.completeRound(match);
        assertEquals(1,match.getRoundHistory().size());assertEquals(1,match.getParticipants().get(players.getFirst().getId()).getScore());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tc096UndecidedRoundResultLeaveDoesNotRecompute(boolean winner) {
        start(3,3,false);round(winner);var deadline=match.getTransitionAt();var history=List.copyOf(match.getRoundHistory());
        var selections=new LinkedHashMap<>(match.getCurrentRound().getSelections());
        var scores=match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        var previous=match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();leave(2);
        assertEquals(deadline,match.getTransitionAt());assertEquals(history,match.getRoundHistory());assertEquals(selections,match.getCurrentRound().getSelections());
        assertEquals(scores,match.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
        assertEquals(previous,match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList());
        clock.now=deadline;service.advanceMatch(match.getId());assertEquals(2,match.getCurrentRound().getRoundNumber());
    }
    @ParameterizedTest @ValueSource(strings={"selecting","winner","draw"})
    void tc097InsufficientPlayersAbortImmediately(String mode) {
        start(2,3,false);if(!mode.equals("selecting"))round(mode.equals("winner"));
        leave(1);assertEnded(MatchEndType.ABORTED);assertEquals(START,saved().getFinishedAt());assertTrue(saved().getWinnerIds().isEmpty());
        assertEquals(UserState.ROOM_NONE,players.get(1).getState());assertEquals(UserState.ROOM_WAITING,players.getFirst().getState());
        assertEquals(mode.equals("selecting")?0:1,saved().getRoundHistory().size());
        assertEquals(mode.equals("winner")?1:0,saved().getFinalScores().get(players.getFirst().getId()));
        var snapshot=saved();service.abortMatch(match.getId());service.handleParticipantLeave(match.getId(),players.getFirst().getId());assertSame(snapshot,saved());
    }
    @ParameterizedTest @ValueSource(strings={"leave","logout"})
    void tc098NormalProtectedAfterEveryoneLeavesAndSameNameRoomRecreated(String operation) {
        start(2,1,false);round(true);var deadline=match.getTransitionAt();var history=List.copyOf(match.getRoundHistory());
        var winners=List.copyOf(match.getPendingWinnerIds());
        for(int i=0;i<2;i++) { if(operation.equals("leave"))leave(i);else auth.logout(sessions.get(i)); }
        assertTrue(rooms.findById(room.getId()).isEmpty());assertSame(match,matches.findById(match.getId()).orElseThrow());
        assertEquals(MatchState.ROUND_RESULT,match.getState());assertEquals(MatchEndType.NORMAL,match.getPendingEndType());
        assertEquals(deadline,match.getTransitionAt());assertEquals(history,match.getRoundHistory());assertEquals(winners,match.getPendingWinnerIds());
        service.abortMatch(match.getId());assertTrue(results.findAll().isEmpty());
        var newRoom=new Room(UUID.randomUUID(),"R",UUID.randomUUID());newRoom.setTargetWins(9);var newMatch=UUID.randomUUID();var last=UUID.randomUUID();
        newRoom.setCurrentMatchId(newMatch);newRoom.setLastCompletedMatchId(last);rooms.save(newRoom);
        users.findAll().forEach(u->users.deleteById(u.getId()));clock.now=deadline;service.advanceMatch(match.getId());
        assertEquals(MatchEndType.NORMAL,saved().getEndType());assertEquals(winners,saved().getWinnerIds());assertEquals(2,saved().getParticipantNames().size());
        assertEquals(newMatch,newRoom.getCurrentMatchId());assertEquals(last,newRoom.getLastCompletedMatchId());assertEquals(9,newRoom.getTargetWins());assertTrue(newRoom.getMemberIds().isEmpty());
    }
    @Test void inactiveRepeatedLeaveCannotTriggerAbortOrRecount() {
        start(3,3,false);leave(2);match.getParticipants().get(players.get(1).getId()).setActive(false);
        service.handleParticipantLeave(match.getId(),players.get(2).getId());assertEquals(MatchState.SELECTING_HAND,match.getState());assertTrue(results.findAll().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"normal","aborted"})
    void tc095SnapshotAloneSurvivesAllCurrentDomainChanges(String mode) {
        start(3,2,true);round(true);leave(2);var quitter=players.get(2).getId();match.getParticipants().get(quitter).setScore(1);
        if(mode.equals("normal")) {clock.now=match.getTransitionAt();service.advanceMatch(match.getId());submit(0,NormalHandType.ROCK);submit(1,NormalHandType.SCISSORS);clock.now=match.getTransitionAt();service.finishNormal(match.getId());}
        else {leave(1);}
        var snapshot=saved();var model=resultService.displayModel(snapshot);var expectedNames=new LinkedHashMap<>(snapshot.getParticipantNames());
        assertEquals(3,expectedNames.size());assertEquals(1,snapshot.getFinalScores().get(quitter));assertEquals(0,snapshot.getFinalScores().get(players.get(1).getId()));
        players.forEach(u->{u.getOriginalHand().setName("現在手");users.deleteById(u.getId());});
        match.getParticipants().values().forEach(p->{p.setScore(99);});
        match.getParticipants().clear();match.getOriginalHands().clear();match.getRoundHistory().clear();match.getWinnerIds().clear();
        rooms.deleteById(room.getId());matches.deleteById(match.getId());
        assertEquals(expectedNames,snapshot.getParticipantNames());assertEquals(model.get("finalScores"),resultService.displayModel(snapshot).get("finalScores"));
        assertEquals(model.get("winners"),resultService.displayModel(snapshot).get("winners"));
        assertEquals(model.get("roundHistory"),resultService.displayModel(snapshot).get("roundHistory"));
        assertEquals(model.get("originalHandAffinities"),resultService.displayModel(snapshot).get("originalHandAffinities"));
        assertEquals(3,snapshot.getOriginalHandAffinities().size());assertEquals("固定手2",snapshot.getOriginalHandAffinities().get(2).getName());
        assertEquals(mode.equals("normal")?MatchEndType.NORMAL:MatchEndType.ABORTED,snapshot.getEndType());
    }
    @Test void finishChangesOnlyParticipantPlayingInSameCurrentRoom() {
        start(3,1,false);round(true);leave(2);var former=players.get(2);var outsider=new GameUser(UUID.randomUUID(),"非参加",START);
        outsider.setState(UserState.READY);outsider.setCurrentRoomId(room.getId());users.save(outsider);room.getMemberIds().add(outsider.getId());
        var waiting=new GameUser(UUID.randomUUID(),"非参加②",START);waiting.setState(UserState.ROOM_WAITING);waiting.setCurrentRoomId(room.getId());users.save(waiting);room.getMemberIds().add(waiting.getId());
        var moved=players.get(1);moved.setCurrentRoomId(UUID.randomUUID());
        clock.now=match.getTransitionAt();service.finishNormal(match.getId());
        assertEquals(UserState.ROOM_WAITING,players.getFirst().getState());assertEquals(UserState.ROOM_NONE,former.getState());
        assertEquals(UserState.PLAYING,moved.getState());assertEquals(UserState.READY,outsider.getState());assertEquals(UserState.ROOM_WAITING,waiting.getState());
    }
    @Test void finishedMatchLeaveOnlyMarksInactiveWithoutChangingSavedResult() {
        start(2,3,false);service.abortMatch(match.getId());var snapshot=saved();var p=match.getParticipants().get(players.getFirst().getId());
        leave(0);service.handleParticipantLeave(match.getId(),p.getUserId());assertFalse(p.isActive());assertSame(snapshot,saved());
        assertEquals(UserState.ROOM_NONE,players.getFirst().getState());
    }
    @Test void manualLeavePreservesIdentityHandAndLoginAndLogoutRemovesOnlyAfterMatchLeave() {
        start(3,3,false);var user=players.getFirst();var hand=user.getOriginalHand();leave(0);
        assertSame(user,access.require(sessions.getFirst()));assertSame(hand,user.getOriginalHand());assertEquals("開始時0",user.getUsername());
        assertEquals(players.get(1).getId(),room.getHostUserId());assertFalse(match.getParticipants().get(user.getId()).isActive());
        auth.logout(sessions.get(1));assertTrue(sessions.get(1).isInvalid());assertTrue(users.findById(players.get(1).getId()).isEmpty());
        assertFalse(match.getParticipants().get(players.get(1).getId()).isActive());assertEnded(MatchEndType.ABORTED);
        assertEquals(UserState.ROOM_WAITING,players.get(2).getState());
    }
    @ParameterizedTest @ValueSource(strings={"normal","aborted"})
    void endedStatusSeparatesCurrentAndDisplayAndProtectsOtherRoom(String mode) {
        start(2,1,false);if(mode.equals("normal")){round(true);clock.now=match.getTransitionAt();service.finishNormal(match.getId());}else service.abortMatch(match.getId());
        var s=status.status(sessions.getFirst(),match.getId().toString());assertNull(s.currentMatchId());assertNull(s.matchState());
        assertEquals(match.getId(),s.displayMatchId());assertEquals(MatchState.MATCH_RESULT,s.displayMatchState());assertNull(s.displayRoundNumber());
        assertNull(s.displayTransitionAt());assertNull(s.selfHandConfirmed());assertNull(s.selfSelectedHand());
        leave(0);assertNull(status.status(sessions.getFirst(),match.getId().toString()).displayMatchId());
    }
    @Test void resultIdStaysM1WhenM2StartsAndFinishesAndNextPlayingWins() {
        start(2,1,false);round(true);clock.now=match.getTransitionAt();service.finishNormal(match.getId());var m1=match;var viewer=sessions.getFirst();
        var extra=new GameUser(UUID.randomUUID(),"Extra",START);extra.setState(UserState.READY);extra.setCurrentRoomId(room.getId());
        extra.setOriginalHand(new OriginalHand(UUID.randomUUID(),"HE",HandRelation.LOSE,HandRelation.DRAW,HandRelation.WIN,HandRelation.LOSE));users.save(extra);room.getMemberIds().add(extra.getId());
        players.get(1).setState(UserState.READY);synchronized(lock){match=matches.findById(service.startMatch(room,List.of(players.get(1),extra))).orElseThrow();}
        assertEquals(m1.getId(),status.status(viewer,m1.getId().toString()).displayMatchId());assertEquals("match-result",resultService.page(viewer,m1.getId().toString()).template());
        service.abortMatch(match.getId());assertEquals(match.getId(),room.getLastCompletedMatchId());assertEquals(m1.getId(),status.status(viewer,m1.getId().toString()).displayMatchId());
        players.getFirst().setState(UserState.READY);players.get(1).setState(UserState.READY);
        synchronized(lock){match=matches.findById(service.startMatch(room,List.of(players.getFirst(),players.get(1)))).orElseThrow();}
        assertEquals("/play?matchId="+match.getId(),resultService.page(viewer,m1.getId().toString()).path());
        assertEquals(match.getId(),status.status(viewer,m1.getId().toString()).currentMatchId());assertEquals(m1.getId(),status.status(viewer,m1.getId().toString()).displayMatchId());
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
    @ParameterizedTest @CsvSource({"leaveSubmit,true","leaveSubmit,false","logoutSubmit,true","logoutSubmit,false",
        "leaveLeave,true","leaveLeave,false","leaveComplete,true","leaveComplete,false","lastSubmitLeave,true","lastSubmitLeave,false",
        "finishLeave,true","finishLeave,false","finishFinish,true","finishFinish,false","abortLeave,true","abortLeave,false",
        "advanceAdvance,true","advanceAdvance,false"})
    void concurrentOperationsRespectBothLockOrders(String operation,boolean firstOrder) throws Exception {
        start(operation.equals("lastSubmitLeave")?3:2,operation.startsWith("finish")?1:3,false);
        Runnable a,b;
        switch(operation) {
            case "leaveSubmit" -> {a=()->leave(0);b=()->submit(0,NormalHandType.ROCK);}
            case "logoutSubmit" -> {a=()->auth.logout(sessions.getFirst());b=()->submit(0,NormalHandType.ROCK);}
            case "leaveLeave" -> {a=()->leave(0);b=()->leave(0);}
            case "leaveComplete" -> {a=()->leave(0);b=()->service.completeRound(match);}
            case "lastSubmitLeave" -> {submit(0,NormalHandType.ROCK);submit(1,NormalHandType.SCISSORS);a=()->submit(2,NormalHandType.PAPER);b=()->leave(2);}
            case "finishLeave" -> {round(true);clock.now=match.getTransitionAt();a=()->service.finishNormal(match.getId());b=()->leave(0);}
            case "finishFinish" -> {round(true);clock.now=match.getTransitionAt();a=()->service.finishNormal(match.getId());b=a;}
            case "abortLeave" -> {a=()->service.abortMatch(match.getId());b=()->leave(0);}
            default -> {round(true);clock.now=match.getTransitionAt();a=()->service.advanceMatch(match.getId());b=a;}
        }
        ordered(firstOrder?a:b,firstOrder?b:a);
        if(operation.equals("advanceAdvance")){assertEquals(2,match.getCurrentRound().getRoundNumber());assertEquals(1,match.getRoundHistory().size());assertTrue(results.findAll().isEmpty());}
        else if(operation.equals("lastSubmitLeave")){assertEquals(MatchState.ROUND_RESULT,match.getState());assertEquals(1,match.getRoundHistory().size());assertTrue(match.getParticipants().values().stream().allMatch(p->p.getScore()<=1));}
        else {assertEnded(operation.startsWith("finish")?MatchEndType.NORMAL:MatchEndType.ABORTED);}
        if(!operation.equals("finishFinish")&&!operation.equals("advanceAdvance")) {
            var departed=players.get(operation.equals("lastSubmitLeave")?2:0);assertEquals(UserState.ROOM_NONE,departed.getState());assertNull(departed.getCurrentRoomId());
        }
    }
    @Test void snapshotAloneResolvesPageAndStatusAfterOriginalMatchDeleted() {
        start(2,3,false);service.abortMatch(match.getId());matches.deleteById(match.getId());
        assertEquals("match-result",resultService.page(sessions.getFirst(),match.getId().toString()).template());
        var s=status.status(sessions.getFirst(),match.getId().toString());assertEquals(MatchState.MATCH_RESULT,s.displayMatchState());
        assertNull(s.currentMatchId());assertNull(s.displayRoundNumber());
    }
    @Test void domainMatchResultWithoutSnapshotDoesNotExposeEndedDisplay() {
        start(2,3,false);match.setState(MatchState.MATCH_RESULT);room.setCurrentMatchId(null);players.forEach(u->u.setState(UserState.ROOM_WAITING));
        assertEquals("/room",resultService.page(sessions.getFirst(),match.getId().toString()).path());
        assertNull(status.status(sessions.getFirst(),match.getId().toString()).displayMatchId());
    }
    @Test void retriesCannotOverwriteNewLastCompletedIdOrScores() {
        start(2,1,false);round(true);clock.now=match.getTransitionAt();service.finishNormal(match.getId());var snapshot=saved();
        var newer=UUID.randomUUID();room.setLastCompletedMatchId(newer);int score=match.getParticipants().get(players.getFirst().getId()).getScore();
        service.advanceMatch(match.getId());service.finishNormal(match.getId());service.abortMatch(match.getId());
        service.handleParticipantLeave(match.getId(),players.getFirst().getId());
        assertEquals(newer,room.getLastCompletedMatchId());assertSame(snapshot,saved());assertEquals(score,match.getParticipants().get(players.getFirst().getId()).getScore());
    }
    @Test void saveResultRequiresSharedLockAndDoesNotReplaceExistingId() {
        start(2,3,false);assertThrows(IllegalStateException.class,()->resultService.saveResult(match,START));service.abortMatch(match.getId());var snapshot=saved();
        match.getParticipants().values().forEach(p->p.setScore(99));synchronized(lock){assertSame(snapshot,resultService.saveResult(match,START.plusSeconds(100)));}assertSame(snapshot,saved());
    }
}
