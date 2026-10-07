package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;

/** 第5段階の①・②だけを対象に、入力境界・不変性・共有ロックを確認する。 */
class OriginalHandServiceTests {
    GameStateLock lock;
    UserStore users;
    RoomStore rooms;
    SessionUserAccess access;
    RoomService roomService;
    AuthService auth;
    OriginalHandService hands;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore();
        access = new SessionUserAccess(users);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        roomService = new RoomService(lock, rooms, access, clock, users, new MatchService(lock, new MatchStore(), clock, rooms, access, new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore())));
        auth = new AuthService(lock, users, access, roomService, clock);
        hands = new OriginalHandService(lock, access, rooms);
    }
    MockHttpSession login(String name) {
        MockHttpServletRequest request = new MockHttpServletRequest(); auth.login(request, name);
        return (MockHttpSession) request.getSession(false);
    }
    GameUser user(MockHttpSession session) { synchronized (lock) { return access.require(session); } }
    MockHttpSession session(boolean inRoom) {
        MockHttpSession s = login("ユーザー"); if (inRoom) roomService.enterRoom(s, "ルーム"); return s;
    }
    OriginalHandForm form(MockHttpSession session, String name) {
        OriginalHandForm f = new OriginalHandForm(); f.setName(name);
        f.setVsRock(HandRelation.WIN); f.setVsScissors(HandRelation.LOSE);
        f.setVsPaper(HandRelation.DRAW); f.setVsOriginal(HandRelation.DRAW);
        UUID id = user(session).getCurrentRoomId();
        f.setReturnPage(id == null ? "ROOMS" : "ROOM"); f.setRoomId(id == null ? null : id.toString());
        return f;
    }
    OriginalHandDeleteForm deletion(OriginalHandForm f) {
        OriginalHandDeleteForm d = new OriginalHandDeleteForm(); d.setReturnPage(f.getReturnPage());
        d.setRoomId(f.getRoomId()); d.setResultMatchId(f.getResultMatchId()); return d;
    }
    List<Object> values(OriginalHand h) {
        return List.of(h.getId(), h.getName(), h.getVsRock(), h.getVsScissors(), h.getVsPaper(), h.getVsOriginal());
    }
    GameOperationException error(int status, Runnable action) {
        GameOperationException e = assertThrows(GameOperationException.class, action::run);
        assertEquals(status, e.getStatus()); assertEquals(status == 400 ? "VALIDATION_ERROR" : status == 401 ? "LOGIN_REQUIRED" : "INVALID_STATE", e.getCode());
        return e;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createUpdateDeleteAndStableId(boolean inRoom) {
        MockHttpSession s = session(inRoom); OriginalHandForm f = form(s, "　初期名\t ");
        String path = inRoom ? "/room" : "/rooms";
        assertEquals(path, hands.save(s, f)); OriginalHand first = user(s).getOriginalHand();
        assertNotNull(first.getId()); assertEquals("初期名", first.getName());
        f.setName(" 更新名 "); f.setVsRock(HandRelation.LOSE); f.setVsScissors(HandRelation.WIN);
        assertEquals(path, hands.save(s, f)); OriginalHand updated = user(s).getOriginalHand();
        assertEquals(first.getId(), updated.getId()); assertEquals("更新名", updated.getName());
        assertEquals(HandRelation.LOSE, updated.getVsRock()); assertEquals(HandRelation.WIN, updated.getVsScissors());
        assertEquals(path, hands.delete(s, deletion(f))); assertNull(user(s).getOriginalHand());
        error(409, () -> hands.delete(s, deletion(f))); assertNull(user(s).getOriginalHand());
        hands.save(s, f); assertNotEquals(first.getId(), user(s).getOriginalHand().getId());
    }

    static Stream<Arguments> names() {
        List<String> valid = List.of(" a ", "　\tあ　", "a".repeat(20), "　" + "😀".repeat(20) + "\t", " 😀 ", "スーパーグー", "グードラゴン", "炎のチョキ", "パーの騎士");
        List<String> invalid = Arrays.asList(null, "", " ", "\t", "　\t ", "a".repeat(21), "😀".repeat(21), "グー", "チョキ", "パー", " グー ", "\tチョキ　", "　パー\t");
        return Stream.of(false, true).flatMap(inRoom -> Stream.of(false, true).flatMap(update ->
            Stream.concat(valid.stream().map(n -> Arguments.of(inRoom, update, n, true)),
                          invalid.stream().map(n -> Arguments.of(inRoom, update, n, false)))));
    }
    @ParameterizedTest @MethodSource("names")
    void nameValidationOnCreateAndUpdate(boolean inRoom, boolean update, String input, boolean valid) {
        MockHttpSession s = session(inRoom); if (update) hands.save(s, form(s, "保存済み"));
        OriginalHand before = user(s).getOriginalHand(); List<Object> old = before == null ? null : values(before);
        OriginalHandForm f = form(s, input); f.setVsRock(HandRelation.LOSE); f.setVsScissors(HandRelation.WIN);
        if (valid) {
            hands.save(s, f); assertEquals(input.strip(), user(s).getOriginalHand().getName());
            if (update) assertEquals(before.getId(), user(s).getOriginalHand().getId());
        } else {
            GameOperationException e = error(400, () -> hands.save(s, f)); assertTrue(e.getFieldErrors().containsKey("name"));
            if (input != null && Set.of("グー", "チョキ", "パー").contains(input.strip())) {
                assertEquals(List.of(OriginalHandService.FORBIDDEN_NAME_MESSAGE), e.getFieldErrors().get("name"));
                assertTrue(e.getErrorMessages().contains(OriginalHandService.FORBIDDEN_NAME_MESSAGE));
            }
            assertSame(before, user(s).getOriginalHand()); if (update) assertEquals(old, values(before));
            assertEquals(input, f.getName());
        }
    }

    static Stream<Arguments> missingRelations() {
        return Stream.of(false, true).flatMap(inRoom -> Stream.of(false, true).flatMap(update ->
                Stream.of(0, 1, 2, 3).map(field -> Arguments.of(inRoom, update, field))));
    }
    @ParameterizedTest @MethodSource("missingRelations")
    void allFourRelationsRequiredAndNoPartialUpdate(boolean inRoom, boolean update, int field) {
        MockHttpSession s = session(inRoom); if (update) hands.save(s, form(s, "保存済み"));
        OriginalHand before = user(s).getOriginalHand(); List<Object> old = before == null ? null : values(before);
        OriginalHandForm f = form(s, "変更名");
        switch (field) { case 0 -> f.setVsRock(null); case 1 -> f.setVsScissors(null); case 2 -> f.setVsPaper(null); case 3 -> f.setVsOriginal(null); }
        GameOperationException e = error(400, () -> hands.save(s, f));
        assertTrue(e.getFieldErrors().containsKey(List.of("vsRock", "vsScissors", "vsPaper", "vsOriginal").get(field)));
        assertSame(before, user(s).getOriginalHand()); if (update) assertEquals(old, values(before));
    }
    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})
    void everyWinDrawOnlyCombinationRejected(int mask) {
        MockHttpSession s = session(false); hands.save(s, form(s, "保存済み"));
        OriginalHand before = user(s).getOriginalHand(); List<Object> old = values(before); OriginalHandForm f = form(s, "変更名");
        HandRelation[] r = new HandRelation[4]; for (int i=0;i<4;i++) r[i] = (mask & (1<<i)) == 0 ? HandRelation.WIN : HandRelation.DRAW;
        f.setVsRock(r[0]); f.setVsScissors(r[1]); f.setVsPaper(r[2]); f.setVsOriginal(r[3]);
        error(400, () -> hands.save(s, f)); assertEquals(old, values(user(s).getOriginalHand()));
        assertSame(before, user(s).getOriginalHand());
    }
    @ParameterizedTest @ValueSource(ints = {0,1,2,3})
    void loseInAnyOneFieldIsAllowed(int field) {
        MockHttpSession s = session(false); OriginalHandForm f = form(s, "手");
        f.setVsRock(HandRelation.WIN); f.setVsScissors(HandRelation.WIN); f.setVsPaper(HandRelation.DRAW); f.setVsOriginal(HandRelation.DRAW);
        switch(field) { case 0 -> f.setVsRock(HandRelation.LOSE); case 1 -> f.setVsScissors(HandRelation.LOSE); case 2 -> f.setVsPaper(HandRelation.LOSE); case 3 -> f.setVsOriginal(HandRelation.LOSE); }
        hands.save(s, f); assertNotNull(user(s).getOriginalHand());
    }
    @Test void forbiddenRuleDoesNotApplyToUsernameOrRoomName() {
        for (String n : List.of("グー", "チョキ", "パー")) {
            MockHttpSession s = login(" " + n + " "); roomService.enterRoom(s, " " + n + " ");
            assertEquals(n, user(s).getUsername()); assertEquals(n, rooms.findById(user(s).getCurrentRoomId()).orElseThrow().getName());
        }
    }

    static Stream<Arguments> targets() {
        return Stream.of(false,true).flatMap(delete -> Stream.of(
            Arguments.of(delete, false, null, null, null, 400),
            Arguments.of(delete, false, "BAD", null, null, 400),
            Arguments.of(delete, false, "ROOMS", "unexpected", null, 400),
            Arguments.of(delete, false, "ROOMS", null, "unexpected", 400),
            Arguments.of(delete, false, "ROOM", null, null, 409),
            Arguments.of(delete, true, "ROOMS", null, null, 409),
            Arguments.of(delete, true, "ROOM", null, null, 400),
            Arguments.of(delete, true, "ROOM", "", null, 400),
            Arguments.of(delete, true, "ROOM", "bad", null, 400),
            Arguments.of(delete, true, "ROOM", "1-1-1-1-1", null, 400),
            Arguments.of(delete, true, "ROOM", UUID.randomUUID().toString(), null, 409)));
    }
    @ParameterizedTest @MethodSource("targets")
    void invalidTargetsNeverChangeHand(boolean delete, boolean inRoom, String page, String id, String result, int status) {
        MockHttpSession s = session(inRoom); hands.save(s, form(s, "保存済み")); List<Object> old = values(user(s).getOriginalHand());
        OriginalHandForm f = form(s, "変更名"); f.setReturnPage(page); f.setRoomId(id); f.setResultMatchId(result);
        error(status, () -> { if (delete) hands.delete(s, deletion(f)); else hands.save(s, f); });
        assertEquals(old, values(user(s).getOriginalHand()));
    }
    @Test void roomResultIdRejectedAndAbsentHandDeleteDoesNotValidateSaveFields() {
        MockHttpSession s = session(true); OriginalHandForm f = form(s, null); f.setResultMatchId("unexpected");
        error(400, () -> hands.save(s, f)); error(400, () -> hands.delete(s, deletion(f))); f.setResultMatchId(null);
        error(409, () -> hands.delete(s, deletion(f))); hands.save(s, form(s,"手"));
        hands.delete(s, deletion(f)); assertNull(user(s).getOriginalHand());
    }
    @Test void anonymousPostsTakePriorityOverInvalidInput() {
        error(401, () -> hands.save(null, new OriginalHandForm()));
        error(401, () -> hands.delete(null, new OriginalHandDeleteForm()));
    }
    @Test void oldRoomCannotSaveOrDeleteAfterLeaveAndReentry() {
        MockHttpSession s = session(true); OriginalHandForm stale = form(s,"手"); hands.save(s,stale);
        List<Object> old = values(user(s).getOriginalHand()); roomService.leaveRoom(s,stale.getRoomId());
        error(409, () -> hands.save(s,stale)); error(409, () -> hands.delete(s,deletion(stale)));
        roomService.enterRoom(s,"別ルーム"); error(409, () -> hands.save(s,stale)); error(409, () -> hands.delete(s,deletion(stale)));
        assertEquals(old,values(user(s).getOriginalHand()));
    }

    @RepeatedTest(20) void saveVersusLeaveIsAtomic() throws Exception { race(false); }
    @RepeatedTest(20) void deleteVersusLeaveIsAtomic() throws Exception { race(true); }
    void race(boolean delete) throws Exception {
        MockHttpSession s=session(true); OriginalHandForm f=form(s,"最初"); hands.save(s,f);
        UUID id=user(s).getOriginalHand().getId(); f.setName("変更済み"); f.setVsRock(HandRelation.LOSE); f.setVsScissors(HandRelation.WIN);
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures=new ArrayList<>();
            for(Runnable action:List.<Runnable>of(() -> {if(delete) hands.delete(s,deletion(f)); else hands.save(s,f);},
                    () -> roomService.leaveRoom(s,f.getRoomId()))) {
                futures.add(pool.submit(() -> {ready.countDown(); assertTrue(start.await(5,TimeUnit.SECONDS));
                    try {action.run(); return "OK";} catch(GameOperationException e){assertEquals(409,e.getStatus());return e.getCode();}}));
            }
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            String result=futures.get(0).get(5,TimeUnit.SECONDS); assertEquals("OK",futures.get(1).get(5,TimeUnit.SECONDS));
            assertTrue(List.of("OK","INVALID_STATE").contains(result));
            synchronized(lock) {
                GameUser u=access.require(s); assertEquals(UserState.ROOM_NONE,u.getState()); assertNull(u.getCurrentRoomId()); assertTrue(rooms.findAll().isEmpty());
                if(delete && result.equals("OK")) assertNull(u.getOriginalHand());
                else {
                    assertEquals(id,u.getOriginalHand().getId()); assertEquals(result.equals("OK")?"変更済み":"最初",u.getOriginalHand().getName());
                    assertEquals(result.equals("OK")?HandRelation.LOSE:HandRelation.WIN,u.getOriginalHand().getVsRock());
                    assertEquals(result.equals("OK")?HandRelation.WIN:HandRelation.LOSE,u.getOriginalHand().getVsScissors());
                    assertEquals(HandRelation.DRAW,u.getOriginalHand().getVsPaper()); assertEquals(HandRelation.DRAW,u.getOriginalHand().getVsOriginal());
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void operationWaitsForSharedLockAndRechecksState(boolean delete) throws Exception {
        MockHttpSession s=session(true); OriginalHandForm f=form(s,"手"); hands.save(s,f); List<Object> old=values(user(s).getOriginalHand());
        try(ExecutorService pool=Executors.newSingleThreadExecutor()) {
            Future<?> future;
            synchronized(lock) {
                CountDownLatch started=new CountDownLatch(1);
                future=pool.submit(() -> {started.countDown(); error(409, () -> {if(delete) hands.delete(s,deletion(f));else hands.save(s,f);});});
                assertTrue(started.await(5,TimeUnit.SECONDS));
                // 共有ロックをこちらが保持している間、操作は完了できない。
                assertThrows(TimeoutException.class, () -> future.get(100,TimeUnit.MILLISECONDS));
                roomService.leaveRoom(s,f.getRoomId());
            }
            future.get(5,TimeUnit.SECONDS);
        }
        assertEquals(old,values(user(s).getOriginalHand()));
    }
    @Test void screenCopiesSavedSettingsSeparatelyFromInput() {
        MockHttpSession s=session(false); OriginalHandForm f=form(s,"最初"); hands.save(s,f);
        ScreenService screens=new ScreenService(lock,access,rooms,users,new com.example.janken.store.MatchStore()); ScreenService.Screen before=screens.current(s);
        f.setName("次"); hands.save(s,f);
        assertEquals("最初",before.model().get("originalHandName"));
        assertEquals("最初",((OriginalHandForm)before.model().get("originalHandForm")).getName());
    }
}
