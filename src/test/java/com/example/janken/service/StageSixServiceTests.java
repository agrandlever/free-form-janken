package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class StageSixServiceTests {
    GameStateLock lock;
    UserStore users;
    RoomStore rooms;
    SessionUserAccess access;
    RoomService service;
    AuthService auth;
    OriginalHandService hands;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore();
        access = new SessionUserAccess(users);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        service = new RoomService(lock, rooms, access, clock, users);
        auth = new AuthService(lock, users, access, service, clock);
        hands = new OriginalHandService(lock, access, rooms);
    }
    MockHttpSession member(String username, String roomName, String handName) {
        MockHttpServletRequest request = new MockHttpServletRequest(); auth.login(request, username);
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        service.enterRoom(session, roomName);
        if (handName != null) { hands.save(session, hand(session, handName)); }
        return session;
    }
    GameUser user(MockHttpSession s) { return access.require(s); }
    Room room(MockHttpSession s) { return rooms.findById(user(s).getCurrentRoomId()).orElseThrow(); }
    String id(MockHttpSession s) { return user(s).getCurrentRoomId().toString(); }
    OriginalHandForm hand(MockHttpSession s, String name) {
        OriginalHandForm f = new OriginalHandForm(); f.setReturnPage("ROOM"); f.setRoomId(id(s));
        f.setName(name); f.setVsRock(HandRelation.LOSE); f.setVsScissors(HandRelation.WIN);
        f.setVsPaper(HandRelation.DRAW); f.setVsOriginal(HandRelation.LOSE); return f;
    }
    OriginalHandDeleteForm delete(MockHttpSession s) {
        OriginalHandDeleteForm f = new OriginalHandDeleteForm(); f.setReturnPage("ROOM"); f.setRoomId(id(s)); return f;
    }
    RoomRuleForm rules(String id, String wins, boolean prevent) {
        RoomRuleForm f = new RoomRuleForm(); f.setRoomId(id); f.setTargetWins(wins);
        f.setPreventConsecutiveSameOriginalHand(prevent); return f;
    }
    GameOperationException error(int status, String code, Runnable operation) {
        GameOperationException e = assertThrows(GameOperationException.class, operation::run);
        assertEquals(status, e.getStatus()); assertEquals(code, e.getCode()); return e;
    }

    @Test void readyCancelAndRepeatedReadyWithUnchangedHand() {
        var s = member("A", "R", "手"); OriginalHand original = user(s).getOriginalHand();
        service.ready(s, id(s)); assertEquals(UserState.READY, user(s).getState());
        error(409, "INVALID_STATE", () -> service.ready(s, id(s)));
        service.cancelReady(s, id(s)); assertEquals(UserState.ROOM_WAITING, user(s).getState());
        service.ready(s, id(s)); assertSame(original, user(s).getOriginalHand());
    }
    @Test void noHandAndWrongTargetsPreserveData() {
        var s = member("A", "R", null); Room r = room(s);
        var e = error(409, "ORIGINAL_HAND_REQUIRED", () -> service.ready(s, id(s)));
        assertEquals(List.of("オリジナル手を作成してから準備完了してください。"), e.getErrorMessages());
        error(409, "INVALID_STATE", () -> service.ready(s, UUID.randomUUID().toString()));
        error(409, "INVALID_STATE", () -> service.cancelReady(s, id(s)));
        assertEquals(UserState.ROOM_WAITING, user(s).getState()); assertNull(user(s).getOriginalHand());
        assertEquals(List.of(user(s).getId()), r.getMemberIds()); assertEquals(3, r.getTargetWins());
        hands.save(s, hand(s, "手")); service.ready(s, id(s));
        error(409, "INVALID_STATE", () -> service.cancelReady(s, UUID.randomUUID().toString()));
        assertEquals(UserState.READY, user(s).getState());
    }
    @Test void loginRequiredForAllNewOperations() {
        error(401, "LOGIN_REQUIRED", () -> service.ready(null, null));
        error(401, "LOGIN_REQUIRED", () -> service.cancelReady(null, null));
        error(401, "LOGIN_REQUIRED", () -> service.updateRules(null, rules(null, "bad", false)));
    }
    @ParameterizedTest @CsvSource({"same,H1,same,H2,READY_USERNAME_CONFLICT", "A,same,B,same,READY_HAND_NAME_CONFLICT", "same,same,same,same,READY_USERNAME_CONFLICT"})
    void waitingDuplicatesAllowedButReadyDuplicatesRejected(String an, String ah, String bn, String bh, String code) {
        var a = member(an, "R", "　" + ah + " "); var b = member(bn, "R", bh);
        service.ready(a, id(a)); // ②の同名は許可。保存済みstrip後の名前で判定する。
        var before = user(b).getOriginalHand();
        var e = error(409, code, () -> service.ready(b, id(b)));
        assertSame(before, user(b).getOriginalHand()); assertEquals(UserState.ROOM_WAITING, user(b).getState());
        List<String> expected = new ArrayList<>();
        if (an.equals(bn)) { expected.add("準備完了中のプレイヤーとユーザー名が重複しています。"); }
        if (ah.equals(bh)) { expected.add("準備完了中のプレイヤーとオリジナル手の名前が重複しています。"); }
        assertEquals(expected, e.getErrorMessages());
    }
    @Test void otherRoomsAndCancelledMembersDoNotConflict() {
        var a = member("same", "R1", "same"); var b = member("same", "R2", "same");
        service.ready(a, id(a)); service.ready(b, id(b)); service.cancelReady(a, id(a));
        var c = member("same", "R1", "same"); service.ready(c, id(c));
        assertEquals(UserState.READY, user(c).getState());
    }
    @ParameterizedTest @ValueSource(strings={"1", "99", "5"})
    void rulesChangeInWaitingAndReadyWithoutCancellingAnyone(String wins) {
        var a = member("A", "R", "HA"); var b = member("B", "R", "HB");
        service.updateRules(a, rules(id(a), wins, true)); assertEquals(Integer.parseInt(wins), room(a).getTargetWins());
        assertTrue(room(a).isPreventConsecutiveSameOriginalHand());
        service.ready(a, id(a)); service.ready(b, id(b));
        service.updateRules(a, rules(id(a), "3", false)); assertFalse(room(a).isPreventConsecutiveSameOriginalHand());
        assertEquals(3, room(a).getTargetWins()); assertEquals(UserState.READY, user(a).getState());
        assertEquals(UserState.READY, user(b).getState());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"0","100","-1","1.5","abc","99999999999999999"," "})
    void invalidRulesPreserveBothRulesAndReady(String wins) {
        var a = member("A", "R", "HA"); service.ready(a, id(a));
        var e = error(400, "VALIDATION_ERROR", () -> service.updateRules(a, rules(id(a), wins, true)));
        assertTrue(e.getFieldErrors().containsKey("targetWins")); assertEquals(3, room(a).getTargetWins());
        assertFalse(room(a).isPreventConsecutiveSameOriginalHand()); assertEquals(UserState.READY, user(a).getState());
    }
    @Test void rulesAuthorizationAndStatePrecedeValueValidation() {
        var a = member("A", "R", "HA"); var b = member("B", "R", "HB");
        error(403, "FORBIDDEN", () -> service.updateRules(b, rules(id(b), "bad", true)));
        error(409, "INVALID_STATE", () -> service.updateRules(b, rules(UUID.randomUUID().toString(), "bad", true)));
        room(a).setCurrentMatchId(UUID.randomUUID());
        error(409, "INVALID_STATE", () -> service.updateRules(a, rules(id(a), "bad", true)));
        error(403, "FORBIDDEN", () -> service.updateRules(b, rules(id(b), "bad", true)));
        assertEquals(3, room(a).getTargetWins()); assertFalse(room(a).isPreventConsecutiveSameOriginalHand());
        room(a).setCurrentMatchId(null); user(a).setState(UserState.ROOM_NONE);
        error(409, "INVALID_STATE", () -> service.updateRules(a, rules(id(a), "bad", true)));
        assertEquals(UserState.ROOM_NONE, user(a).getState());
    }
    @Test void readyRejectsStaleSaveAndDeleteAndEnter() {
        var s = member("A", "R", "手"); var save = hand(s, "変更"); var delete = delete(s);
        OriginalHand before = user(s).getOriginalHand(); service.ready(s, id(s));
        error(409, "INVALID_STATE", () -> hands.save(s, save));
        error(409, "INVALID_STATE", () -> hands.delete(s, delete));
        error(409, "INVALID_STATE", () -> service.enterRoom(s, "other"));
        assertSame(before, user(s).getOriginalHand()); assertEquals(UserState.READY, user(s).getState());
    }
    @Test void readyHostLeaveTransfersToEarliestAndLastLeaveDeletesRoom() {
        var a = member("A", "R", "HA"); var b = member("B", "R", "HB"); var c = member("C", "R", "HC");
        GameUser before = user(a); OriginalHand hand = before.getOriginalHand(); Room r = room(a);
        service.ready(a, id(a)); service.ready(b, id(b)); service.ready(c, id(c));
        service.leaveRoom(a, id(a)); assertEquals(UserState.ROOM_NONE, before.getState()); assertNull(before.getCurrentRoomId());
        assertSame(before, user(a)); assertEquals("A", before.getUsername()); assertSame(hand, before.getOriginalHand());
        assertEquals(user(b).getId(), r.getHostUserId()); assertEquals(UserState.READY, user(b).getState());
        service.leaveRoom(b, id(b)); assertEquals(user(c).getId(), r.getHostUserId());
        service.leaveRoom(c, id(c)); assertTrue(rooms.findById(r.getId()).isEmpty()); assertTrue(rooms.findByName("R").isEmpty());
    }
    @Test void readyLogoutTransfersHostAndDeletesLastRoomAndUser() {
        var a = member("A", "R", "HA"); var b = member("B", "R", "HB");
        UUID aid = user(a).getId(), bid = user(b).getId(); Room r = room(a);
        service.ready(a, id(a)); service.ready(b, id(b)); auth.logout(a);
        assertTrue(a.isInvalid()); assertTrue(users.findById(aid).isEmpty()); assertEquals(bid, r.getHostUserId());
        auth.logout(b); assertTrue(b.isInvalid()); assertTrue(users.findById(bid).isEmpty()); assertTrue(rooms.findAll().isEmpty());
    }

    /** バリアで同時に開始し、タイムアウト付きFutureでスレッド内の失敗も検出する。 */
    List<String> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Runnable action : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try { action.run(); return "OK"; }
                    catch (GameOperationException e) { return e.getCode(); }
                }));
            }
            return List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @ParameterizedTest @CsvSource({"same,H1,same,H2,READY_USERNAME_CONFLICT", "A,same,B,same,READY_HAND_NAME_CONFLICT", "same,same,same,same,READY_USERNAME_CONFLICT"})
    void simultaneousDuplicatesOnlyOneSucceeds(String an, String ah, String bn, String bh, String code) throws Exception {
        for (int i = 0; i < 30; i++) {
            setup(); var a = member(an, "R", ah); var b = member(bn, "R", bh);
            String id = id(a); var outcomes = race(() -> service.ready(a, id), () -> service.ready(b, id));
            assertEquals(1, Collections.frequency(outcomes, "OK")); assertEquals(1, Collections.frequency(outcomes, code));
            assertEquals(1, users.findAll().stream().filter(u -> u.getState() == UserState.READY).count());
        }
    }
    @ParameterizedTest @ValueSource(strings={"save","delete","cancel","leave","logout","rules"})
    void readyRacesMaintainConsistentState(String operation) throws Exception {
        for (int i = 0; i < 30; i++) {
            setup(); var a = member("A", "R", "HA"); var b = member("B", "R", "HB");
            GameUser u = user(a); Room r = room(a); String id = id(a); var save = hand(a, "new"); var delete = delete(a);
            OriginalHand original = u.getOriginalHand();
            Runnable other = switch (operation) {
                case "save" -> () -> hands.save(a, save);
                case "delete" -> () -> hands.delete(a, delete);
                case "cancel" -> () -> service.cancelReady(a, id);
                case "leave" -> () -> service.leaveRoom(a, id);
                case "logout" -> () -> auth.logout(a);
                default -> () -> service.updateRules(a, rules(id, "5", true));
            };
            var out = race(() -> service.ready(a, id), other);
            switch (operation) {
                case "save" -> {
                    assertEquals("OK", out.getFirst()); assertEquals(UserState.READY, u.getState());
                    if (out.get(1).equals("OK")) { assertEquals("new", u.getOriginalHand().getName()); }
                    else { assertEquals("INVALID_STATE", out.get(1)); assertSame(original, u.getOriginalHand()); }
                }
                case "delete" -> {
                    assertEquals(1, Collections.frequency(out, "OK"));
                    if (u.getState() == UserState.READY) { assertSame(original, u.getOriginalHand()); assertEquals("INVALID_STATE", out.get(1)); }
                    else { assertEquals("ORIGINAL_HAND_REQUIRED", out.getFirst()); assertNull(u.getOriginalHand()); }
                }
                case "cancel" -> {
                    assertEquals("OK", out.getFirst());
                    assertEquals(out.get(1).equals("OK") ? UserState.ROOM_WAITING : UserState.READY, u.getState());
                    assertTrue(List.of("OK", "INVALID_STATE").contains(out.get(1)));
                }
                case "leave", "logout" -> {
                    assertEquals("OK", out.get(1)); assertEquals(UserState.ROOM_NONE, u.getState()); assertNull(u.getCurrentRoomId());
                    assertFalse(r.getMemberIds().contains(u.getId())); assertEquals(user(b).getId(), r.getHostUserId());
                    assertSame(original, u.getOriginalHand());
                    if (operation.equals("logout")) { assertTrue(a.isInvalid()); assertTrue(users.findById(u.getId()).isEmpty()); }
                    else { assertSame(u, user(a)); }
                    assertTrue(List.of("OK", operation.equals("logout") ? "LOGIN_REQUIRED" : "INVALID_STATE").contains(out.getFirst()));
                }
                case "rules" -> {
                    assertEquals(List.of("OK", "OK"), out); assertEquals(UserState.READY, u.getState());
                    assertEquals(5, r.getTargetWins()); assertTrue(r.isPreventConsecutiveSameOriginalHand());
                }
            }
            assertEquals(UserState.ROOM_WAITING, user(b).getState()); assertNull(r.getCurrentMatchId());
            for (UUID memberId : r.getMemberIds()) { assertEquals(r.getId(), users.findById(memberId).orElseThrow().getCurrentRoomId()); }
        }
    }
}
