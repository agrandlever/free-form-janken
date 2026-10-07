package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameMatchTests {

    @Test
    void holdsRoomIdentityAndFixedRules() {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        GameMatch match = new GameMatch(id, roomId, "ルーム", 5, true);
        assertEquals(id, match.getId());
        assertEquals(roomId, match.getRoomId());
        assertEquals("ルーム", match.getRoomName());
        assertEquals(5, match.getTargetWins());
        assertTrue(match.isPreventConsecutiveSameOriginalHand());
    }

    @Test
    void hasAllRequiredInitialValuesWithoutCreatingRoundOrParticipants() {
        GameMatch match = new GameMatch(UUID.randomUUID(), UUID.randomUUID(), "ルーム", 3, false);
        assertEquals(MatchState.SELECTING_HAND, match.getState());
        assertNull(match.getTransitionAt());
        assertNull(match.getPendingEndType());
        assertTrue(match.getPendingWinnerIds().isEmpty());
        assertNull(match.getEndType());
        assertTrue(match.getWinnerIds().isEmpty());
        assertTrue(match.getRoundHistory().isEmpty());
        assertTrue(match.getParticipants().isEmpty());
        assertTrue(match.getOriginalHands().isEmpty());
        assertNull(match.getCurrentRound());
    }

    @Test
    void canHoldLaterSuppliedProgressDataAndMultipleWinners() {
        GameMatch match = new GameMatch(UUID.randomUUID(), UUID.randomUUID(), "ルーム", 3, false);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MatchParticipant participant = new MatchParticipant(first, "参加者", UUID.randomUUID());
        OriginalHandSnapshot hand = new OriginalHandSnapshot(participant.getOriginalHandId(), first, "炎",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.DRAW);
        Round round = new Round(2, Instant.EPOCH);
        RoundResult result = new RoundResult(1, List.of(), false, Instant.EPOCH);
        match.getParticipants().put(first, participant);
        match.getOriginalHands().add(hand);
        match.setCurrentRound(round);
        match.getRoundHistory().add(result);
        match.setState(MatchState.ROUND_RESULT);
        match.setTransitionAt(Instant.EPOCH.plusSeconds(10));
        match.setPendingEndType(MatchEndType.NORMAL);
        match.getPendingWinnerIds().addAll(List.of(first, second));
        match.setEndType(MatchEndType.NORMAL);
        match.getWinnerIds().addAll(List.of(first, second));
        assertSame(participant, match.getParticipants().get(first));
        assertSame(hand, match.getOriginalHands().getFirst());
        assertSame(round, match.getCurrentRound());
        assertEquals(List.of(result), match.getRoundHistory());
        assertEquals(MatchState.ROUND_RESULT, match.getState());
        assertEquals(Instant.EPOCH.plusSeconds(10), match.getTransitionAt());
        assertEquals(MatchEndType.NORMAL, match.getPendingEndType());
        assertEquals(List.of(first, second), match.getPendingWinnerIds());
        assertEquals(MatchEndType.NORMAL, match.getEndType());
        assertEquals(List.of(first, second), match.getWinnerIds());
        assertEquals(0, participant.getScore());
        assertNull(participant.getPreviousHand());
    }

    @Test
    void collectionsAreIndependentBetweenMatchesAndNoRoomReferenceIsHeld() {
        GameMatch first = new GameMatch(UUID.randomUUID(), UUID.randomUUID(), "A", 3, false);
        GameMatch second = new GameMatch(UUID.randomUUID(), UUID.randomUUID(), "B", 3, false);
        first.getParticipants().put(UUID.randomUUID(), new MatchParticipant(UUID.randomUUID(), "参加者", UUID.randomUUID()));
        first.getOriginalHands().add(new OriginalHandSnapshot(UUID.randomUUID(), UUID.randomUUID(), "炎",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.DRAW));
        first.getRoundHistory().add(new RoundResult(1, List.of(), false, Instant.EPOCH));
        first.getPendingWinnerIds().add(UUID.randomUUID());
        first.getWinnerIds().add(UUID.randomUUID());
        assertTrue(second.getParticipants().isEmpty());
        assertTrue(second.getOriginalHands().isEmpty());
        assertTrue(second.getRoundHistory().isEmpty());
        assertTrue(second.getPendingWinnerIds().isEmpty());
        assertTrue(second.getWinnerIds().isEmpty());
        assertFalse(Arrays.stream(GameMatch.class.getDeclaredFields()).anyMatch(field -> field.getType() == Room.class));
    }

}
