package com.example.janken.domain;

import com.example.janken.domain.enums.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchParticipantTests {

    @Test
    void holdsStartIdentityAndInitialState() {
        UUID userId = UUID.randomUUID();
        UUID handId = UUID.randomUUID();
        MatchParticipant participant = new MatchParticipant(userId, "参加者", handId);
        assertEquals(userId, participant.getUserId());
        assertEquals("参加者", participant.getUsername());
        assertEquals(handId, participant.getOriginalHandId());
        assertEquals(0, participant.getScore());
        assertTrue(participant.isActive());
        assertNull(participant.getPreviousHand());
    }

    @Test
    void allowsProgressValuesToBeAssignedWithoutBusinessProcessing() {
        MatchParticipant participant = new MatchParticipant(UUID.randomUUID(), "参加者", UUID.randomUUID());
        HandSelection previous = new HandSelection(SelectedHandType.ORIGINAL, null, UUID.randomUUID());
        participant.setScore(2);
        participant.setActive(false);
        participant.setPreviousHand(previous);
        assertEquals(2, participant.getScore());
        assertFalse(participant.isActive());
        assertSame(previous, participant.getPreviousHand());
        participant.setPreviousHand(null);
        assertNull(participant.getPreviousHand());
    }

    @Test
    void usernameIsIndependentOfCurrentUser() {
        GameUser user = new GameUser(UUID.randomUUID(), "開始時", Instant.EPOCH);
        MatchParticipant participant = new MatchParticipant(user.getId(), user.getUsername(), UUID.randomUUID());
        user = new GameUser(user.getId(), "変更後", Instant.EPOCH);
        assertEquals("開始時", participant.getUsername());
    }

}
