package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchResultSnapshotTests {

    private final UUID matchId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID departed = UUID.randomUUID();
    private final UUID handId = UUID.randomUUID();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final List<UUID> winners = new ArrayList<>(List.of(first, second));
    private final List<RoundResultEntry> entries = new ArrayList<>(List.of(
            new RoundResultEntry(first, "A", "炎", true),
            new RoundResultEntry(second, "B", "グー", true),
            new RoundResultEntry(departed, "退出者", "チョキ", false)));
    private final RoundResult round = new RoundResult(2, entries, true, Instant.EPOCH.plusSeconds(5));
    private final OriginalHandSnapshot hand = new OriginalHandSnapshot(handId, departed, "炎",
            HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.WIN);
    private final List<RoundResult> history = new ArrayList<>(List.of(round));
    private final List<OriginalHandSnapshot> hands = new ArrayList<>(List.of(hand));

    MatchResultSnapshotTests() {
        names.put(first, "A");
        names.put(second, "B");
        names.put(departed, "退出者");
        scores.put(first, 3);
        scores.put(second, 3);
        scores.put(departed, 0);
    }

    private MatchResultSnapshot snapshot() {
        return new MatchResultSnapshot(matchId, roomId, "開始時ルーム", 3, true, names,
                MatchEndType.NORMAL, winners, scores, history, hands, Instant.EPOCH.plusSeconds(15));
    }

    @Test
    void holdsEveryFieldIncludingMultipleWinnersAndZeroScoreDepartedParticipant() {
        MatchResultSnapshot result = snapshot();
        assertEquals(matchId, result.getMatchId());
        assertEquals(roomId, result.getRoomId());
        assertEquals("開始時ルーム", result.getRoomName());
        assertEquals(3, result.getTargetWins());
        assertTrue(result.isPreventConsecutiveSameOriginalHand());
        assertEquals(names, result.getParticipantNames());
        assertEquals(MatchEndType.NORMAL, result.getEndType());
        assertEquals(List.of(first, second), result.getWinnerIds());
        assertEquals(scores, result.getFinalScores());
        assertEquals("退出者", result.getParticipantNames().get(departed));
        assertEquals(0, result.getFinalScores().get(departed));
        assertEquals(result.getParticipantNames().keySet(), result.getFinalScores().keySet());
        assertEquals(List.of(first, second, departed), new ArrayList<>(result.getParticipantNames().keySet()));
        RoundResult storedRound = result.getRoundHistory().getFirst();
        assertEquals(2, storedRound.getRoundNumber());
        assertTrue(storedRound.isHasWinner());
        assertEquals(Instant.EPOCH.plusSeconds(5), storedRound.getDecidedAt());
        assertEquals(3, storedRound.getEntries().size());
        for (int i = 0; i < entries.size(); i++) {
            RoundResultEntry source = entries.get(i);
            RoundResultEntry stored = storedRound.getEntries().get(i);
            assertEquals(source.getUserId(), stored.getUserId());
            assertEquals(source.getUsername(), stored.getUsername());
            assertEquals(source.getHandName(), stored.getHandName());
            assertEquals(source.isWonRound(), stored.isWonRound());
        }
        OriginalHandSnapshot storedHand = result.getOriginalHandAffinities().getFirst();
        assertEquals(handId, storedHand.getHandId());
        assertEquals(departed, storedHand.getOwnerUserId());
        assertEquals("炎", storedHand.getName());
        assertEquals(HandRelation.WIN, storedHand.getVsRock());
        assertEquals(HandRelation.LOSE, storedHand.getVsScissors());
        assertEquals(HandRelation.DRAW, storedHand.getVsPaper());
        assertEquals(HandRelation.WIN, storedHand.getVsOriginal());
        assertEquals(Instant.EPOCH.plusSeconds(15), result.getFinishedAt());
    }

    @Test
    void copiesBothMapsIncludingEntriesBeforeSourceChanges() {
        MatchResultSnapshot result = snapshot();
        names.replaceAll((id, name) -> "変更後");
        scores.replaceAll((id, score) -> 99);
        names.clear();
        scores.clear();
        assertEquals(Map.of(first, "A", second, "B", departed, "退出者"), result.getParticipantNames());
        assertEquals(Map.of(first, 3, second, 3, departed, 0), result.getFinalScores());
    }

    @Test
    void copiesAllInputListsBeforeSourceChanges() {
        MatchResultSnapshot result = snapshot();
        winners.clear();
        history.set(0, new RoundResult(99, List.of(), false, Instant.MAX));
        history.clear();
        hands.set(0, new OriginalHandSnapshot(UUID.randomUUID(), first, "変更後",
                HandRelation.LOSE, HandRelation.WIN, HandRelation.WIN, HandRelation.LOSE));
        hands.clear();
        entries.clear();
        assertEquals(List.of(first, second), result.getWinnerIds());
        assertEquals(2, result.getRoundHistory().getFirst().getRoundNumber());
        assertEquals(3, result.getRoundHistory().getFirst().getEntries().size());
        assertEquals("炎", result.getOriginalHandAffinities().getFirst().getName());
    }

    @Test
    void regeneratesNestedRoundResultsAndEntries() {
        MatchResultSnapshot result = snapshot();
        RoundResult stored = result.getRoundHistory().getFirst();
        assertNotSame(round, stored);
        assertNotSame(round.getEntries(), stored.getEntries());
        for (int i = 0; i < stored.getEntries().size(); i++) {
            assertNotSame(round.getEntries().get(i), stored.getEntries().get(i));
            assertNotSame(entries.get(i), stored.getEntries().get(i));
        }
    }

    @Test
    void regeneratesOriginalHandSnapshots() {
        MatchResultSnapshot result = snapshot();
        assertNotSame(hand, result.getOriginalHandAffinities().getFirst());
        assertNotSame(hands, result.getOriginalHandAffinities());
    }

    @Test
    void getterCollectionsCannotChangeInternalData() {
        MatchResultSnapshot result = snapshot();
        assertThrows(UnsupportedOperationException.class, () -> result.getParticipantNames().put(first, "変更"));
        assertThrows(UnsupportedOperationException.class, () -> result.getParticipantNames().entrySet().iterator().next().setValue("変更"));
        assertThrows(UnsupportedOperationException.class, () -> result.getParticipantNames().keySet().remove(first));
        assertThrows(UnsupportedOperationException.class, () -> result.getFinalScores().put(first, 99));
        assertThrows(UnsupportedOperationException.class, () -> result.getFinalScores().entrySet().iterator().next().setValue(99));
        assertThrows(UnsupportedOperationException.class, () -> result.getWinnerIds().add(departed));
        assertThrows(UnsupportedOperationException.class, () -> result.getRoundHistory().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getRoundHistory().set(0, round));
        assertThrows(UnsupportedOperationException.class, () -> result.getRoundHistory().getFirst().getEntries().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getOriginalHandAffinities().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getOriginalHandAffinities().set(0, hand));
        assertEquals("A", result.getParticipantNames().get(first));
        assertEquals(3, result.getFinalScores().get(first));
    }

    @Test
    void currentMatchUserRoomAndOriginalHandChangesCannotAffectResult() {
        GameUser user = new GameUser(departed, "退出者", Instant.EPOCH);
        OriginalHand currentHand = new OriginalHand(handId, "炎", HandRelation.WIN,
                HandRelation.LOSE, HandRelation.DRAW, HandRelation.WIN);
        user.setOriginalHand(currentHand);
        Room room = new Room(roomId, "開始時ルーム", first);
        room.setPreventConsecutiveSameOriginalHand(true);
        GameMatch match = new GameMatch(matchId, room.getId(), room.getName(), room.getTargetWins(),
                room.isPreventConsecutiveSameOriginalHand());
        MatchParticipant participant = new MatchParticipant(departed, user.getUsername(), handId);
        participant.setActive(false);
        match.getParticipants().put(departed, participant);
        match.getRoundHistory().add(round);
        match.getOriginalHands().add(new OriginalHandSnapshot(currentHand.getId(), user.getId(), currentHand.getName(),
                currentHand.getVsRock(), currentHand.getVsScissors(), currentHand.getVsPaper(), currentHand.getVsOriginal()));
        // テスト側で値を用意するだけで、終了結果生成Serviceは作らない。
        MatchResultSnapshot result = new MatchResultSnapshot(match.getId(), match.getRoomId(), match.getRoomName(),
                match.getTargetWins(), match.isPreventConsecutiveSameOriginalHand(),
                Map.of(departed, participant.getUsername()), MatchEndType.ABORTED, List.of(),
                Map.of(departed, participant.getScore()), match.getRoundHistory(), match.getOriginalHands(), Instant.EPOCH);
        participant.setScore(99);
        match.getParticipants().clear();
        match.getRoundHistory().clear();
        match.getOriginalHands().clear();
        user = new GameUser(user.getId(), "変更後", Instant.EPOCH);
        currentHand.setName("水");
        currentHand.setVsRock(HandRelation.LOSE);
        user.setOriginalHand(null);
        room.setTargetWins(99);
        room.setPreventConsecutiveSameOriginalHand(false);
        assertEquals("退出者", result.getParticipantNames().get(departed));
        assertEquals(0, result.getFinalScores().get(departed));
        assertEquals("炎", result.getOriginalHandAffinities().getFirst().getName());
        assertEquals(HandRelation.WIN, result.getOriginalHandAffinities().getFirst().getVsRock());
        assertEquals(1, result.getRoundHistory().size());
        assertEquals(3, result.getTargetWins());
        assertTrue(result.isPreventConsecutiveSameOriginalHand());
        assertEquals(MatchEndType.ABORTED, result.getEndType());
        assertTrue(result.getWinnerIds().isEmpty());
    }

    @Test
    void rejectsMismatchedParticipantKeySets() {
        scores.remove(departed);
        assertThrows(IllegalArgumentException.class, this::snapshot);
        scores.put(UUID.randomUUID(), 0);
        assertThrows(IllegalArgumentException.class, this::snapshot);
    }

    @Test
    void acceptsEmptyCollectionsWithoutAddingWinnerBusinessRules() {
        MatchResultSnapshot result = new MatchResultSnapshot(matchId, roomId, "ルーム", 3, false,
                Map.of(), MatchEndType.ABORTED, List.of(), Map.of(), List.of(), List.of(), Instant.EPOCH);
        assertTrue(result.getParticipantNames().isEmpty());
        assertTrue(result.getFinalScores().isEmpty());
        assertTrue(result.getWinnerIds().isEmpty());
        assertTrue(result.getRoundHistory().isEmpty());
        assertTrue(result.getOriginalHandAffinities().isEmpty());
    }

    @Test
    void snapshotAndNestedTypesAreImmutableAndHaveNoHandSelectionField() {
        for (Class<?> type : List.of(MatchResultSnapshot.class, RoundResult.class,
                RoundResultEntry.class, OriginalHandSnapshot.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()));
            assertTrue(Arrays.stream(type.getDeclaredFields()).allMatch(field -> Modifier.isFinal(field.getModifiers())));
            assertFalse(Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().startsWith("set")));
            assertFalse(Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getType() == HandSelection.class));
        }
    }

}
