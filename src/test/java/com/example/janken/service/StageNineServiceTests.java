package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageNineServiceTests {
    GameStateLock lock; UserStore users; RoomStore rooms; MatchStore matches; SessionUserAccess access;
    Clock clock; RoundJudgeService judge; MatchService service; GameMatch match; Room room;
    List<MockHttpSession> sessions;
    Instant now=Instant.parse("2026-10-07T00:00:00Z");
    @BeforeEach void setup() { fixture(3,3,true); }
    void fixture(int count,int target,boolean prevent) {
        lock=new GameStateLock(); users=new UserStore(); rooms=new RoomStore(); matches=new MatchStore(); access=new SessionUserAccess(users);
        clock=mock(Clock.class); when(clock.instant()).thenReturn(now); judge=spy(new RoundJudgeService());
        service=new MatchService(lock,matches,clock,rooms,access,judge, users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore()));
        room=new Room(UUID.randomUUID(),"R",UUID.randomUUID()); rooms.save(room);
        match=new GameMatch(UUID.randomUUID(),room.getId(),"R",target,prevent); match.setCurrentRound(new Round(1,now));
        room.setCurrentMatchId(match.getId()); matches.save(match); sessions=new ArrayList<>();
        for(int i=0;i<count;i++) {
            var u=new GameUser(UUID.randomUUID(),"U"+i,now); u.setState(UserState.PLAYING); u.setCurrentRoomId(room.getId()); users.save(u); room.getMemberIds().add(u.getId());
            var s=new MockHttpSession(); s.setAttribute(SessionUserAccess.USER_ID,u.getId()); sessions.add(s);
            UUID handId=UUID.randomUUID(); match.getParticipants().put(u.getId(),new MatchParticipant(u.getId(),u.getUsername(),handId));
            match.getOriginalHands().add(new OriginalHandSnapshot(handId,u.getId(),"H"+i,HandRelation.WIN,HandRelation.LOSE,HandRelation.DRAW,HandRelation.LOSE));
        }
    }
    UUID uid(int i) { return access.require(sessions.get(i)).getId(); }
    HandSelectionForm form(String type,String normal,String original) {
        var f=new HandSelectionForm(); f.setMatchId(match.getId().toString()); f.setRoundNumber("1"); f.setType(type); f.setNormalHand(normal); f.setOriginalHandId(original); return f;
    }
    void submit(int i,String normal) { service.submitHand(sessions.get(i),form("NORMAL",normal,null)); }
    void error(int status,Runnable action) {
        var e=assertThrows(GameOperationException.class,action::run); assertEquals(status,e.getStatus()); assertEquals(status==400?"VALIDATION_ERROR":"INVALID_STATE",e.getCode());
    }
    @ParameterizedTest @ValueSource(strings={"ROCK","SCISSORS","PAPER"})
    void acceptsNormalAndCopiesForm(String hand) {
        var f=form("NORMAL",hand,""); service.submitHand(sessions.getFirst(),f); f.setNormalHand("PAPER");
        assertEquals(NormalHandType.valueOf(hand),match.getCurrentRound().getSelections().get(uid(0)).getNormalHand());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void acceptsEverySnapshotIncludingOtherOwnerAndPreviousHand(int index) {
        fixture(3,3,false);
        UUID id=match.getOriginalHands().get(index).getHandId();
        match.getParticipants().get(uid(0)).setPreviousHand(new HandSelection(SelectedHandType.ORIGINAL,null,id));
        // 設定OFFでは直前と同じ手も許可する。全参加者の開始時手を利用できる。
        var f=form("ORIGINAL","",id.toString()); service.submitHand(sessions.getFirst(),f); f.setOriginalHandId(UUID.randomUUID().toString());
        assertEquals(id,match.getCurrentRound().getSelections().get(uid(0)).getOriginalHandId());
    }
    @ParameterizedTest @CsvSource(value={"NORMAL|~|~","NORMAL|ROCK|bad","NORMAL|bad|~","ORIGINAL|~|~","ORIGINAL|ROCK|bad","ORIGINAL|~|bad","bad|ROCK|~","~|~|~","NORMAL| |~","ORIGINAL| |bad"},delimiter='|',nullValues="~")
    void invalidHandsDoNotRegister(String type,String normal,String original) {
        error(400,()->service.submitHand(sessions.getFirst(),form(type,normal,original)));
        assertTrue(match.getCurrentRound().getSelections().isEmpty()); assertTrue(match.getRoundHistory().isEmpty());
    }
    @Test void unknownOriginalIdRejected() { error(400,()->service.submitHand(sessions.getFirst(),form("ORIGINAL",null,UUID.randomUUID().toString()))); assertTrue(match.getCurrentRound().getSelections().isEmpty()); }
    @ParameterizedTest @ValueSource(strings={"same","different","invalid"})
    void duplicateAlways409AndNeverOverwrites(String variant) {
        submit(0,"ROCK"); var saved=match.getCurrentRound().getSelections().get(uid(0));
        error(409,()->service.submitHand(sessions.getFirst(),form(variant.equals("invalid")?"bad":"NORMAL",variant.equals("different")?"PAPER":"ROCK","invalid")));
        assertSame(saved,match.getCurrentRound().getSelections().get(uid(0))); assertEquals(1,match.getCurrentRound().getSelections().size());
    }
    @ParameterizedTest @ValueSource(strings={"state","inactive","nonparticipant","noRound","oldRound","oldMatch","missingMatch","missingRoom","noRoom","roomMatch","matchId"})
    void targetAndStateBeforeHandValidation(String kind) {
        var f=form("invalid","invalid","invalid");
        switch(kind) {
            case "state" -> match.setState(MatchState.ROUND_RESULT);
            case "inactive" -> match.getParticipants().get(uid(0)).setActive(false);
            case "nonparticipant" -> match.getParticipants().remove(uid(0));
            case "noRound" -> match.setCurrentRound(null);
            case "oldRound" -> match.setCurrentRound(new Round(2,now));
            case "oldMatch" -> f.setMatchId(UUID.randomUUID().toString());
            case "missingMatch" -> matches.deleteById(match.getId());
            case "missingRoom" -> rooms.deleteById(room.getId());
            case "noRoom" -> access.require(sessions.getFirst()).setCurrentRoomId(null);
            case "roomMatch" -> room.setCurrentMatchId(null);
            case "matchId" -> { var wrong=new GameMatch(UUID.randomUUID(),room.getId(),"R",3,false); whenMatchIdMismatch(wrong); }
        }
        error(409,()->service.submitHand(sessions.getFirst(),f)); assertTrue(match.getRoundHistory().isEmpty());
    }
    void whenMatchIdMismatch(GameMatch wrong) {
        var store=mock(MatchStore.class); when(store.findById(match.getId())).thenReturn(Optional.of(wrong)); service=new MatchService(lock,store,clock,rooms,access,judge, users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore()));
    }
    @ParameterizedTest @EnumSource(value=UserState.class,names={"ROOM_NONE","ROOM_WAITING","READY"})
    void onlyPlaying(UserState state) { access.require(sessions.getFirst()).setState(state); error(409,()->service.submitHand(sessions.getFirst(),form("bad",null,null))); }
    @Test void anonymous401() { assertEquals(401,assertThrows(GameOperationException.class,()->service.submitHand(null,form("bad",null,null))).getStatus()); }
    @ParameterizedTest @CsvSource(value={"matchId|~","matchId|","matchId|bad","matchId|1-1-1-1-1","roundNumber|~","roundNumber|","roundNumber|bad","roundNumber|0","roundNumber|-1","roundNumber|2147483648"},delimiter='|',nullValues="~")
    void requiredTargetFormat(String field,String value) {
        var f=form("bad",null,null); if(field.equals("matchId")) {f.setMatchId(value);} else {f.setRoundNumber(value);}
        error(400,()->service.submitHand(sessions.getFirst(),f)); assertTrue(match.getCurrentRound().getSelections().isEmpty());
    }
    @ParameterizedTest @CsvSource({"ROCK,SCISSORS,SCISSORS,1,10","ROCK,ROCK,SCISSORS,2,10","ROCK,SCISSORS,PAPER,0,5","ROCK,ROCK,ROCK,0,5"})
    void completesMultiplayerOnce(String a,String b,String c,int winners,int seconds) {
        submit(0,a); submit(1,b); service.completeRound(match); assertTrue(match.getRoundHistory().isEmpty()); verifyNoInteractions(clock);
        submit(2,c); assertCompleted(winners,seconds);
        var result=match.getRoundHistory().getFirst(); var deadline=match.getTransitionAt(); var previous=match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();
        service.completeRound(match); assertSame(result,match.getRoundHistory().getFirst()); assertEquals(deadline,match.getTransitionAt());
        assertEquals(previous,match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList()); assertCompleted(winners,seconds);
        assertNull(match.getPendingEndType()); assertTrue(match.getPendingWinnerIds().isEmpty());
        for(var p:match.getParticipants().values()) {
            var current=match.getCurrentRound().getSelections().get(p.getUserId()); assertNotSame(current,p.getPreviousHand());
            var value=p.getPreviousHand().getNormalHand(); current.setNormalHand(NormalHandType.PAPER); assertEquals(value,p.getPreviousHand().getNormalHand());
        }
    }
    void assertCompleted(int winners,int seconds) {
        assertEquals(MatchState.ROUND_RESULT,match.getState()); assertEquals(1,match.getRoundHistory().size());
        var r=match.getRoundHistory().getFirst(); assertEquals(now,r.getDecidedAt()); assertEquals(now.plusSeconds(seconds),match.getTransitionAt());
        assertEquals(winners,r.getEntries().stream().filter(RoundResultEntry::isWonRound).count());
        for(var e:r.getEntries()) { var p=match.getParticipants().get(e.getUserId()); assertEquals(e.isWonRound()?1:0,p.getScore()); assertNotNull(p.getPreviousHand()); }
        verify(clock,times(1)).instant(); verify(judge,times(1)).judgeRound(eq(1),anyList(),anyMap(),anyList(),eq(now));
        assertNull(match.getEndType()); assertTrue(match.getWinnerIds().isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void finalWinnersIncludesAllAndTenSeconds(int winners) {
        fixture(winners+1,1,true); for(int i=0;i<winners;i++) {submit(i,"ROCK");} submit(winners,"SCISSORS"); assertCompleted(winners,10);
        assertEquals(MatchEndType.NORMAL,match.getPendingEndType()); assertEquals(sessions.subList(0,winners).stream().map(s->access.require(s).getId()).toList(),match.getPendingWinnerIds());
    }
    @Test void twoPlayerRound() {fixture(2,3,false); submit(0,"PAPER"); submit(1,"ROCK"); assertCompleted(1,10);}
    @ParameterizedTest @ValueSource(ints={0,1})
    void tooFewActiveNeverComplete(int active) {
        for(int i=0;i<3;i++){match.getParticipants().get(uid(i)).setActive(i<active); match.getCurrentRound().getSelections().put(uid(i),new HandSelection(SelectedHandType.NORMAL,NormalHandType.ROCK,null));}
        service.completeRound(match); assertTrue(match.getRoundHistory().isEmpty()); verifyNoInteractions(clock,judge);
    }
    @Test void inactiveExcludedAndPreserved() {
        var p=match.getParticipants().get(uid(2)); p.setActive(false); p.setScore(8); var previous=new HandSelection(SelectedHandType.NORMAL,NormalHandType.PAPER,null); p.setPreviousHand(previous);
        submit(0,"ROCK"); submit(1,"SCISSORS"); assertEquals(2,match.getRoundHistory().getFirst().getEntries().size()); assertEquals(8,p.getScore()); assertSame(previous,p.getPreviousHand()); assertTrue(match.getPendingWinnerIds().isEmpty());
    }
    @Test void multipleRoundsUseOnlyTestDataAndUpdateOriginalCopies() {
        // 同一手の連続使用を含むため、設定OFFで第9段階のラウンド確定を回帰確認する。
        fixture(3,3,false);
        UUID id=match.getOriginalHands().get(1).getHandId();
        for(int n=1;n<=2;n++) {
            if(n==2){match.setCurrentRound(new Round(2,now)); match.setState(MatchState.SELECTING_HAND);}
            for(int i=0;i<3;i++){ var f=form("ORIGINAL",null,id.toString()); f.setRoundNumber(Integer.toString(n)); service.submitHand(sessions.get(i),f); }
            assertEquals(n,match.getRoundHistory().size()); for(var p:match.getParticipants().values()){ assertEquals(id,p.getPreviousHand().getOriginalHandId()); assertEquals(0,p.getScore()); assertNotSame(p.getPreviousHand(),match.getCurrentRound().getSelections().get(p.getUserId())); }
        }
        assertEquals(2,match.getCurrentRound().getRoundNumber()); assertEquals(MatchState.ROUND_RESULT,match.getState());
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","lastTwo","lastDuplicate","complete"})
    void concurrentOperationsFinalizeOnce(String scenario) throws Exception {
        fixture(2,3,true);
        Runnable a,b;
        switch(scenario) {
            case "duplicate" -> { a=()->submit(0,"ROCK"); b=()->submit(0,"PAPER"); }
            case "lastTwo" -> { a=()->submit(0,"ROCK"); b=()->submit(1,"SCISSORS"); }
            case "lastDuplicate" -> { submit(0,"ROCK"); a=()->submit(1,"SCISSORS"); b=()->submit(1,"SCISSORS"); }
            default -> { match.getCurrentRound().getSelections().put(uid(0),new HandSelection(SelectedHandType.NORMAL,NormalHandType.ROCK,null)); match.getCurrentRound().getSelections().put(uid(1),new HandSelection(SelectedHandType.NORMAL,NormalHandType.SCISSORS,null)); a=()->service.completeRound(match); b=a; }
        }
        var pool=Executors.newFixedThreadPool(2); var gate=new CyclicBarrier(2);
        try {
            var fa=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);return outcome(a);}); var fb=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);return outcome(b);});
            var results=List.of(fa.get(10,TimeUnit.SECONDS),fb.get(10,TimeUnit.SECONDS));
            if(scenario.equals("duplicate")) { assertEquals(1,Collections.frequency(results,409)); assertEquals(1,match.getCurrentRound().getSelections().size()); verifyNoInteractions(judge,clock); }
            else { assertCompleted(1,10); assertEquals(2,match.getCurrentRound().getSelections().size()); assertEquals(scenario.equals("lastDuplicate")?1:0,Collections.frequency(results,409)); }
        } finally {pool.shutdownNow();}
    }
    int outcome(Runnable r) {try{r.run();return 302;}catch(GameOperationException e){return e.getStatus();}}

    @Test void stalePendingInformationClearsWhenNotReached() {
        match.setPendingEndType(MatchEndType.NORMAL); match.getPendingWinnerIds().add(uid(0));
        submit(0,"ROCK"); submit(1,"ROCK"); submit(2,"ROCK");
        assertNull(match.getPendingEndType()); assertTrue(match.getPendingWinnerIds().isEmpty()); assertCompleted(0,5);
    }
    @Test void accumulatedScoresReachTargetAndTimeRemainsFixed() {
        fixture(2,3,true); match.getParticipants().get(uid(0)).setScore(2);
        submit(0,"ROCK"); submit(1,"SCISSORS");
        assertEquals(3,match.getParticipants().get(uid(0)).getScore()); assertEquals(List.of(uid(0)),match.getPendingWinnerIds());
        assertEquals(MatchEndType.NORMAL,match.getPendingEndType()); assertEquals(now.plusSeconds(10),match.getTransitionAt());
        when(clock.instant()).thenReturn(now.plusSeconds(100)); service.completeRound(match);
        assertEquals(now.plusSeconds(10),match.getTransitionAt()); assertEquals(3,match.getParticipants().get(uid(0)).getScore()); verify(clock,times(1)).instant();
    }
    @Test void completionWithoutRoundDoesNothing() {match.setCurrentRound(null);service.completeRound(match);assertTrue(match.getRoundHistory().isEmpty());verifyNoInteractions(clock,judge);}
    @Test void judgeRunsInsideSharedLockAndGetsExactlyOneTime() {
        doAnswer(invocation -> { assertTrue(Thread.holdsLock(lock)); return invocation.callRealMethod(); })
                .when(judge).judgeRound(anyInt(),anyList(),anyMap(),anyList(),any());
        submit(0,"ROCK");submit(1,"SCISSORS");submit(2,"SCISSORS");assertCompleted(1,10);
    }
}
