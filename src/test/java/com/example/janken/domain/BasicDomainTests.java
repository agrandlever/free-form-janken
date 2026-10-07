package com.example.janken.domain;

import com.example.janken.domain.enums.HandRelation;
import com.example.janken.domain.enums.NormalHandType;
import com.example.janken.domain.enums.SelectedHandType;
import com.example.janken.domain.enums.UserState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BasicDomainTests {

    @Test
    void gameUserStartsWithDesignedLoginState() {
        UUID id = UUID.randomUUID();
        Instant loginTime = Instant.parse("2026-10-07T00:00:00Z");
        GameUser user = new GameUser(id, "ユーザー", loginTime);

        assertEquals(id, user.getId());
        assertEquals("ユーザー", user.getUsername());
        assertEquals(UserState.ROOM_NONE, user.getState());
        assertNull(user.getCurrentRoomId());
        assertNull(user.getOriginalHand());
        assertNull(user.getJoinedRoomAt());
        assertEquals(loginTime, user.getLastSeenAt());
    }

    @Test
    void gameUserCanHoldRoomStateAndOriginalHand() {
        Instant enteredAt = Instant.parse("2026-10-07T00:01:00Z");
        GameUser user = new GameUser(UUID.randomUUID(), "ユーザー", enteredAt.minusSeconds(60));
        UUID roomId = UUID.randomUUID();
        OriginalHand hand = new OriginalHand(UUID.randomUUID(), "ドラゴン",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE);

        // 入室処理は実装せず、その処理が設定する状態を保持できることだけを確認する。
        user.setState(UserState.ROOM_WAITING);
        user.setCurrentRoomId(roomId);
        user.setOriginalHand(hand);
        user.setJoinedRoomAt(enteredAt);
        user.setLastSeenAt(enteredAt);

        assertEquals(UserState.ROOM_WAITING, user.getState());
        assertEquals(roomId, user.getCurrentRoomId());
        assertSame(hand, user.getOriginalHand());
        assertEquals(enteredAt, user.getJoinedRoomAt());
        assertEquals(enteredAt, user.getLastSeenAt());
    }

    @Test
    void originalHandsWithSameNameKeepSeparateIdsAndSettings() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        OriginalHand first = new OriginalHand(firstId, "ドラゴン",
                HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE);
        OriginalHand second = new OriginalHand(secondId, "ドラゴン",
                HandRelation.DRAW, HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW);

        assertEquals(firstId, first.getId());
        assertEquals(secondId, second.getId());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(first.getName(), second.getName());
        assertEquals(HandRelation.WIN, first.getVsRock());
        assertEquals(HandRelation.LOSE, first.getVsScissors());
        assertEquals(HandRelation.DRAW, first.getVsPaper());
        assertEquals(HandRelation.LOSE, first.getVsOriginal());
    }

    @Test
    void roomKeepsInitialRulesAndMemberOrderWithoutMatchObjects() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Room room = new Room(roomId, "ルーム", hostId);

        assertEquals(roomId, room.getId());
        assertEquals("ルーム", room.getName());
        assertEquals(hostId, room.getHostUserId());
        assertTrue(room.getMemberIds().isEmpty());
        assertEquals(3, room.getTargetWins());
        assertFalse(room.isPreventConsecutiveSameOriginalHand());
        assertNull(room.getCurrentMatchId());
        assertNull(room.getLastCompletedMatchId());

        // ホストの自動登録や人数制限は行わず、List自体の参加順保持を確認する。
        room.getMemberIds().add(hostId);
        room.getMemberIds().add(memberId);
        assertEquals(List.of(hostId, memberId), room.getMemberIds());
        room.getMemberIds().remove(hostId);
        room.setHostUserId(memberId);
        room.setTargetWins(5);
        room.setPreventConsecutiveSameOriginalHand(true);
        UUID currentMatchId = UUID.randomUUID();
        UUID completedMatchId = UUID.randomUUID();
        room.setCurrentMatchId(currentMatchId);
        room.setLastCompletedMatchId(completedMatchId);

        assertEquals(List.of(memberId), room.getMemberIds());
        assertEquals(memberId, room.getHostUserId());
        assertEquals(5, room.getTargetWins());
        assertTrue(room.isPreventConsecutiveSameOriginalHand());
        assertEquals(currentMatchId, room.getCurrentMatchId());
        assertEquals(completedMatchId, room.getLastCompletedMatchId());
    }

    @Test
    void handSelectionCanRepresentEveryNormalHand() {
        for (NormalHandType normalHand : NormalHandType.values()) {
            HandSelection selection = new HandSelection(SelectedHandType.NORMAL, normalHand, null);

            assertEquals(SelectedHandType.NORMAL, selection.getType());
            assertEquals(normalHand, selection.getNormalHand());
            assertNull(selection.getOriginalHandId());
        }
    }

    @Test
    void handSelectionCanRepresentOriginalHand() {
        UUID handId = UUID.randomUUID();
        HandSelection selection = new HandSelection(SelectedHandType.ORIGINAL, null, handId);

        assertEquals(SelectedHandType.ORIGINAL, selection.getType());
        assertNull(selection.getNormalHand());
        assertEquals(handId, selection.getOriginalHandId());
    }
}
