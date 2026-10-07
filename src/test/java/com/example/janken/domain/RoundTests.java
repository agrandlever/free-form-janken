package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoundTests {

    @Test
    void startsWithEmptySelectionsAndSuppliedNumberAndTime() {
        Instant startedAt = Instant.parse("2026-10-07T00:00:00Z");
        Round round = new Round(3, startedAt);
        assertEquals(3, round.getRoundNumber());
        assertEquals(startedAt, round.getStartedAt());
        assertTrue(round.getSelections().isEmpty());
    }

    @Test
    void supportsMutableSelectionsForLaterProcessing() {
        Round round = new Round(1, Instant.EPOCH);
        UUID userId = UUID.randomUUID();
        HandSelection selection = new HandSelection(SelectedHandType.NORMAL, NormalHandType.ROCK, null);
        round.getSelections().put(userId, selection);
        assertSame(selection, round.getSelections().get(userId));
        selection.setNormalHand(NormalHandType.PAPER);
        assertEquals(NormalHandType.PAPER, round.getSelections().get(userId).getNormalHand());
        round.getSelections().remove(userId);
        assertTrue(round.getSelections().isEmpty());
    }

    @Test
    void newRoundsDoNotShareSelections() {
        Round first = new Round(1, Instant.EPOCH);
        Round second = new Round(2, Instant.EPOCH);
        first.getSelections().put(UUID.randomUUID(), new HandSelection(SelectedHandType.NORMAL, NormalHandType.ROCK, null));
        assertTrue(second.getSelections().isEmpty());
    }

}
