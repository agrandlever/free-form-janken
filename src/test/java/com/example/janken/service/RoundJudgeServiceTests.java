package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundJudgeServiceTests {
    RoundJudgeService judge = new RoundJudgeService();
    Instant now = Instant.parse("2026-10-07T00:00:00Z");
    HandSelection normal(NormalHandType h) { return new HandSelection(SelectedHandType.NORMAL,h,null); }
    HandSelection original(UUID id) { return new HandSelection(SelectedHandType.ORIGINAL,null,id); }
    OriginalHandSnapshot snapshot(HandRelation relation) {
        return new OriginalHandSnapshot(UUID.randomUUID(),UUID.randomUUID(),"同名",HandRelation.WIN,HandRelation.LOSE,HandRelation.DRAW,relation);
    }
    @ParameterizedTest @CsvSource({"ROCK,SCISSORS,WIN","SCISSORS,PAPER,WIN","PAPER,ROCK,WIN",
        "SCISSORS,ROCK,LOSE","PAPER,SCISSORS,LOSE","ROCK,PAPER,LOSE","ROCK,ROCK,DRAW","SCISSORS,SCISSORS,DRAW","PAPER,PAPER,DRAW"})
    void normalPairs(NormalHandType a,NormalHandType b,HandRelation expected) {
        assertEquals(expected,judge.compareHands(normal(a),normal(b),List.of()));
    }
    @ParameterizedTest @CsvSource({"ROCK,WIN,LOSE","SCISSORS,LOSE,WIN","PAPER,DRAW,DRAW"})
    void originalAgainstNormalBothDirections(NormalHandType h,HandRelation expected,HandRelation reverse) {
        var s=snapshot(HandRelation.DRAW); var hands=List.of(s);
        assertEquals(expected,judge.compareHands(original(s.getHandId()),normal(h),hands));
        assertEquals(reverse,judge.compareHands(normal(h),original(s.getHandId()),hands));
    }
    @ParameterizedTest @CsvSource({"WIN,LOSE,WIN","LOSE,WIN,LOSE","WIN,WIN,DRAW","LOSE,LOSE,DRAW",
        "DRAW,WIN,LOSE","DRAW,LOSE,WIN","WIN,DRAW,WIN","LOSE,DRAW,LOSE","DRAW,DRAW,DRAW"})
    void differentOriginalNinePairsEvenWithSameName(HandRelation a,HandRelation b,HandRelation expected) {
        var sa=snapshot(a); var sb=snapshot(b);
        assertEquals(expected,judge.compareHands(original(sa.getHandId()),original(sb.getHandId()),List.of(sa,sb)));
    }
    @Test void sameHandIdDraws() {
        var s=snapshot(HandRelation.WIN);
        assertEquals(HandRelation.DRAW,judge.compareHands(original(s.getHandId()),original(s.getHandId()),List.of(s)));
    }
    @ParameterizedTest @CsvSource({"ROCK,SCISSORS,SCISSORS,1","ROCK,ROCK,SCISSORS,2","ROCK,SCISSORS,PAPER,0","ROCK,ROCK,ROCK,0"})
    void multiplayerAndPureCalculation(NormalHandType a,NormalHandType b,NormalHandType c,int winners) {
        var match=new GameMatch(UUID.randomUUID(),UUID.randomUUID(),"R",3,false);
        match.setCurrentRound(new Round(7,now));
        var types=List.of(a,b,c); var previous=normal(NormalHandType.PAPER);
        for(int i=0;i<3;i++) {
            var p=new MatchParticipant(UUID.randomUUID(),"開始名"+i,UUID.randomUUID()); p.setScore(4+i); p.setPreviousHand(previous);
            match.getParticipants().put(p.getUserId(),p); match.getCurrentRound().getSelections().put(p.getUserId(),normal(types.get(i)));
        }
        var store=new MatchStore(); store.save(match); var before=store.findAll();
        var result=judge.judgeRound(7,List.copyOf(match.getParticipants().values()),match.getCurrentRound().getSelections(),List.of(),now);
        assertEquals(winners,result.getEntries().stream().filter(RoundResultEntry::isWonRound).count());
        assertEquals(winners>0,result.isHasWinner()); assertEquals(now,result.getDecidedAt()); assertEquals(7,result.getRoundNumber());
        int i=0; for(var p:match.getParticipants().values()) {
            assertEquals(4+i,p.getScore()); assertSame(previous,p.getPreviousHand());
            assertEquals("開始名"+i,result.getEntries().get(i).getUsername()); i++;
        }
        assertEquals(before,store.findAll()); assertSame(match,store.findById(match.getId()).orElseThrow());
        assertEquals(MatchState.SELECTING_HAND,match.getState()); assertTrue(match.getRoundHistory().isEmpty()); assertNull(match.getTransitionAt());
        assertEquals(List.of("グー","チョキ","パー").get(a.ordinal()),result.getEntries().getFirst().getHandName());
        assertThrows(UnsupportedOperationException.class,()->result.getEntries().clear());
        assertTrue(Arrays.stream(RoundResultEntry.class.getDeclaredFields()).noneMatch(f->f.getName().equals("score")));
    }
    @Test void originalHandNameComesFromSnapshot() {
        var s=snapshot(HandRelation.DRAW); var p=new MatchParticipant(UUID.randomUUID(),"開始名",s.getHandId());
        var result=judge.judgeRound(2,List.of(p),Map.of(p.getUserId(),original(s.getHandId())),List.of(s),now);
        assertEquals("同名",result.getEntries().getFirst().getHandName());
    }
}
