package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 勝敗計算だけを担当し、入力やStoreの状態を変更しない。 */
@Service
public class RoundJudgeService {
    public RoundResult judgeRound(int roundNumber, List<MatchParticipant> activeParticipants,
            Map<UUID, HandSelection> selections, List<OriginalHandSnapshot> originalHands, Instant decidedAt) {
        List<RoundResultEntry> entries = new ArrayList<>();
        for (MatchParticipant participant : activeParticipants) {
            HandSelection hand = selections.get(participant.getUserId());
            boolean hasWin = false;
            boolean hasLoss = false;
            for (MatchParticipant other : activeParticipants) {
                if (participant.getUserId().equals(other.getUserId())) { continue; }
                HandRelation relation = compareHands(hand, selections.get(other.getUserId()), originalHands);
                hasWin |= relation == HandRelation.WIN;
                hasLoss |= relation == HandRelation.LOSE;
            }
            entries.add(new RoundResultEntry(participant.getUserId(), participant.getUsername(),
                    handName(hand, originalHands), hasWin && !hasLoss));
        }
        return new RoundResult(roundNumber, entries, entries.stream().anyMatch(RoundResultEntry::isWonRound), decidedAt);
    }

    HandRelation compareHands(HandSelection a, HandSelection b, List<OriginalHandSnapshot> hands) {
        if (a.getType() == SelectedHandType.NORMAL && b.getType() == SelectedHandType.NORMAL) {
            if (a.getNormalHand() == b.getNormalHand()) { return HandRelation.DRAW; }
            boolean win = switch (a.getNormalHand()) {
                case ROCK -> b.getNormalHand() == NormalHandType.SCISSORS;
                case SCISSORS -> b.getNormalHand() == NormalHandType.PAPER;
                case PAPER -> b.getNormalHand() == NormalHandType.ROCK;
            };
            return win ? HandRelation.WIN : HandRelation.LOSE;
        }
        if (a.getType() == SelectedHandType.NORMAL) { return reverse(compareHands(b, a, hands)); }
        OriginalHandSnapshot original = original(a, hands);
        if (b.getType() == SelectedHandType.NORMAL) {
            return switch (b.getNormalHand()) {
                case ROCK -> original.getVsRock();
                case SCISSORS -> original.getVsScissors();
                case PAPER -> original.getVsPaper();
            };
        }
        if (a.getOriginalHandId().equals(b.getOriginalHandId())) { return HandRelation.DRAW; }
        // WIN > DRAW > LOSEの順で双方の希望を比較すると、仕様の9通りを表せる。
        int comparison = Integer.compare(rank(original.getVsOriginal()), rank(original(b, hands).getVsOriginal()));
        return comparison > 0 ? HandRelation.WIN : comparison < 0 ? HandRelation.LOSE : HandRelation.DRAW;
    }
    private int rank(HandRelation relation) {
        return switch (relation) { case WIN -> 1; case DRAW -> 0; case LOSE -> -1; };
    }
    private HandRelation reverse(HandRelation relation) {
        return switch (relation) { case WIN -> HandRelation.LOSE; case LOSE -> HandRelation.WIN; case DRAW -> HandRelation.DRAW; };
    }
    private static OriginalHandSnapshot original(HandSelection hand, List<OriginalHandSnapshot> hands) {
        return hands.stream().filter(h -> h.getHandId().equals(hand.getOriginalHandId())).findFirst().orElseThrow();
    }
    public static String handName(HandSelection hand, List<OriginalHandSnapshot> hands) {
        if (hand.getType() == SelectedHandType.ORIGINAL) { return original(hand, hands).getName(); }
        return switch (hand.getNormalHand()) { case ROCK -> "グー"; case SCISSORS -> "チョキ"; case PAPER -> "パー"; };
    }
}
