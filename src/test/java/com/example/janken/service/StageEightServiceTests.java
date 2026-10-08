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

class StageEightServiceTests {
    GameStateLock lock;
    UserStore users;
    RoomStore rooms;
    SessionUserAccess access;
    RoomService service;
    AuthService auth;
    OriginalHandService hands;
    MatchStore matches;
    Clock clock;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore();
        access = new SessionUserAccess(users);
        clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        service = new RoomService(lock, rooms, access, clock, users, new MatchService(lock, matches = new MatchStore(), clock, rooms, access, new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore())));
        auth = new AuthService(lock, users, access, service, clock);
        hands = new OriginalHandService(lock, access, rooms, new com.example.janken.store.MatchResultStore());
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


    GameMatch started(MockHttpSession host) {
        var result = service.startMatch(host, id(host));
        return matches.findById(result.matchId()).orElseThrow();
    }
    List<MockHttpSession> prepared(int count, boolean hostReady) {
        List<MockHttpSession> sessions = new ArrayList<>();
        sessions.add(member("Host", "R", "HH"));
        if (hostReady) { service.ready(sessions.getFirst(), id(sessions.getFirst())); }
        for (int i = 1; i <= count - (hostReady ? 1 : 0); i++) {
            var s = member("U" + i, "R", "H" + i); service.ready(s, id(s)); sessions.add(s);
        }
        return sessions;
    }
    @ParameterizedTest @CsvSource({"2,true", "3,true", "7,true", "2,false", "3,false"})
    void startsOnlyReadyInMemberOrderAndPreservesAllInitialValues(int count, boolean hostReady) {
        var sessions = prepared(count, hostReady); var host = sessions.getFirst(); var waiting = member("Waiting", "R", "HW");
        Room r = room(host); UUID last = UUID.randomUUID(); r.setLastCompletedMatchId(last);
        List<GameUser> ready = r.getMemberIds().stream().map(i -> users.findById(i).orElseThrow())
                .filter(u -> u.getState() == UserState.READY).toList();
        GameMatch m = started(host);
        assertEquals(ready.stream().map(GameUser::getId).toList(), new ArrayList<>(m.getParticipants().keySet()));
        assertEquals(count, m.getParticipants().size()); assertEquals(1, matches.findAll().size());
        assertEquals(m.getId(), r.getCurrentMatchId()); assertEquals(last, r.getLastCompletedMatchId());
        assertEquals(r.getId(), m.getRoomId()); assertEquals("R", m.getRoomName()); assertEquals(3, m.getTargetWins());
        assertFalse(m.isPreventConsecutiveSameOriginalHand()); assertEquals(MatchState.SELECTING_HAND, m.getState());
        assertEquals(1, m.getCurrentRound().getRoundNumber()); assertTrue(m.getCurrentRound().getSelections().isEmpty());
        assertEquals(clock.instant(), m.getCurrentRound().getStartedAt());
        assertNull(m.getTransitionAt()); assertNull(m.getPendingEndType()); assertNull(m.getEndType());
        assertTrue(m.getPendingWinnerIds().isEmpty()); assertTrue(m.getWinnerIds().isEmpty()); assertTrue(m.getRoundHistory().isEmpty());
        for (int i=0;i<ready.size();i++) {
            var u=ready.get(i); var p=m.getParticipants().get(u.getId()); var h=m.getOriginalHands().get(i);
            assertEquals(u.getId(), p.getUserId()); assertEquals(u.getUsername(), p.getUsername());
            assertEquals(u.getOriginalHand().getId(), p.getOriginalHandId()); assertEquals(0, p.getScore());
            assertTrue(p.isActive()); assertNull(p.getPreviousHand()); assertEquals(UserState.PLAYING,u.getState());
            assertEquals(u.getId(),h.getOwnerUserId()); assertEquals(p.getOriginalHandId(),h.getHandId());
        }
        assertEquals(hostReady ? UserState.PLAYING : UserState.ROOM_WAITING, user(host).getState());
        assertEquals(UserState.ROOM_WAITING,user(waiting).getState()); assertFalse(m.getParticipants().containsKey(user(waiting).getId()));
    }
    @Test void snapshotsAndRulesRemainFixedAfterSourceReplacementAndDeletion() {
        var sessions=prepared(2,true); var host=sessions.getFirst(); var r=room(host);
        service.updateRules(host,rules(id(host),"9",true)); var m=started(host); var u=user(host);
        var original=u.getOriginalHand(); var snap=m.getOriginalHands().getFirst();
        u.setOriginalHand(new OriginalHand(UUID.randomUUID(),"Changed",HandRelation.WIN,HandRelation.LOSE,HandRelation.LOSE,HandRelation.WIN));
        user(sessions.get(1)).setOriginalHand(null);
        // Room.name・GameUser.usernameは不変なので、同じIDを持つ元データをStoreで差し替える。
        Room changedRoom = new Room(r.getId(), "ChangedRoom", r.getHostUserId()); rooms.save(changedRoom);
        GameUser changedUser = new GameUser(u.getId(), "ChangedUser", clock.instant()); users.save(changedUser);
        r.setTargetWins(1); r.setPreventConsecutiveSameOriginalHand(false);
        assertEquals("R",m.getRoomName()); assertEquals(9,m.getTargetWins()); assertTrue(m.isPreventConsecutiveSameOriginalHand());
        assertEquals("Host",m.getParticipants().get(u.getId()).getUsername());
        assertEquals(original.getId(),snap.getHandId()); assertEquals("HH",snap.getName());
        assertEquals(HandRelation.LOSE,snap.getVsRock()); assertEquals(HandRelation.WIN,snap.getVsScissors());
        assertEquals(HandRelation.DRAW,snap.getVsPaper()); assertEquals(HandRelation.LOSE,snap.getVsOriginal());
        assertEquals("H1",m.getOriginalHands().get(1).getName());
    }
    @ParameterizedTest @ValueSource(strings={"nonhost","wrongRoom","state","running","zero","one","missing","username","hand","both"})
    void finalValidationRejectsWithoutChangingSharedData(String fault) {
        var sessions=prepared(2,true); var host=sessions.getFirst(); var other=sessions.get(1); Room r=room(host);
        MockHttpSession actor=host; String target=id(host); String code="INVALID_STATE"; int status=409;
        switch(fault) {
            case "nonhost" -> { actor=other; code="FORBIDDEN"; status=403; }
            case "wrongRoom" -> target=UUID.randomUUID().toString();
            case "state" -> user(host).setState(UserState.PLAYING);
            case "running" -> r.setCurrentMatchId(UUID.randomUUID());
            case "zero" -> { user(host).setState(UserState.ROOM_WAITING); user(other).setState(UserState.ROOM_WAITING); }
            case "one" -> user(other).setState(UserState.ROOM_WAITING);
            case "missing" -> { user(other).setOriginalHand(null); code="ORIGINAL_HAND_REQUIRED"; }
            case "username", "both" -> {
                // usernameは不変なので、同じID・所属を持つテスト用ユーザーをStoreへ置き換える。
                var before=user(other); var replacement=new GameUser(before.getId(),"Host",clock.instant());
                replacement.setCurrentRoomId(r.getId()); replacement.setState(UserState.READY);
                replacement.setOriginalHand(before.getOriginalHand()); users.save(replacement); code="READY_USERNAME_CONFLICT";
            }
            default -> { }
        }
        if (fault.equals("hand") || fault.equals("both")) {
            var h=user(host).getOriginalHand(); user(other).setOriginalHand(new OriginalHand(UUID.randomUUID(),h.getName(),h.getVsRock(),h.getVsScissors(),h.getVsPaper(),h.getVsOriginal()));
            if (fault.equals("hand")) { code="READY_HAND_NAME_CONFLICT"; }
        }
        var states=users.findAll().stream().map(GameUser::getState).toList(); var handsBefore=users.findAll().stream().map(GameUser::getOriginalHand).toList();
        UUID current=r.getCurrentMatchId(); var selected=actor; var roomId=target;
        var e=error(status,code,()->service.startMatch(selected,roomId));
        if (fault.equals("both")) { assertEquals(2,e.getErrorMessages().size()); }
        assertTrue(matches.findAll().isEmpty()); assertEquals(current,r.getCurrentMatchId());
        assertEquals(states,users.findAll().stream().map(GameUser::getState).toList());
        assertEquals(handsBefore,users.findAll().stream().map(GameUser::getOriginalHand).toList());
        assertEquals(3,r.getTargetWins()); assertFalse(r.isPreventConsecutiveSameOriginalHand());
    }
    @Test void startRequiresLoginAndMatchServiceRequiresHeldLock() {
        error(401,"LOGIN_REQUIRED",()->service.startMatch(null,null));
        var sessions=prepared(2,true); var host=sessions.getFirst();
        assertThrows(IllegalStateException.class,()->new MatchService(lock,matches,clock,rooms,access,new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore()))
                .startMatch(room(host),sessions.stream().map(this::user).toList()));
        assertTrue(matches.findAll().isEmpty()); assertNull(room(host).getCurrentMatchId());
    }
    @Test void screenModelIsACopyWithoutAffinities() {
        var sessions=prepared(2,true); var host=sessions.getFirst(); var m=started(host);
        var screens=new ScreenService(lock,access,rooms,users,matches); var screen=screens.current(host);
        m.getParticipants().get(user(host).getId()).setScore(7); m.getOriginalHands().clear();
        assertEquals(0,screen.model().get("score"));
        assertEquals(List.of(new ScreenService.ParticipantView("Host",0),new ScreenService.ParticipantView("U1",0)),screen.model().get("participants"));
        assertEquals(List.of("HH","H1"),screen.model().get("originalHandNames"));
        assertFalse(screen.model().containsKey("originalHands"));
    }
    @Test void clockFailureDuringConstructionLeavesNoPartialState() {
        var sessions=prepared(2,true); var host=sessions.getFirst(); var r=room(host);
        Clock broken=org.mockito.Mockito.mock(Clock.class); org.mockito.Mockito.when(broken.instant()).thenThrow(new IllegalStateException("clock"));
        var failing=new RoomService(lock,rooms,access,clock,users,new MatchService(lock,matches,broken,rooms,access,new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore())));
        assertThrows(IllegalStateException.class,()->failing.startMatch(host,id(host)));
        assertTrue(matches.findAll().isEmpty()); assertNull(r.getCurrentMatchId());
        sessions.forEach(s->assertEquals(UserState.READY,user(s).getState()));
    }
    @ParameterizedTest @ValueSource(strings={"save","delete","ready","cancel","rules","start","leave","logout"})
    void stalePlayingOperationsAreRejectedWithoutChanges(String operation) {
        var sessions=prepared(2,true); var host=sessions.getFirst(); String id=id(host); var r=room(host); var u=user(host);
        var save=hand(host,"New"); var delete=delete(host); var old=u.getOriginalHand(); var m=started(host);
        Runnable action=switch(operation){
            case "save" -> ()->hands.save(host,save); case "delete" -> ()->hands.delete(host,delete);
            case "ready" -> ()->service.ready(host,id); case "cancel" -> ()->service.cancelReady(host,id);
            case "rules" -> ()->service.updateRules(host,rules(id,"5",true)); case "start" -> ()->service.startMatch(host,id);
                case "leave" -> ()->service.leaveRoom(host,id); default -> ()->auth.logout(host);
        };
        if (operation.equals("leave") || operation.equals("logout")) {
            action.run();
            assertEquals(UserState.ROOM_NONE, u.getState()); assertNull(u.getCurrentRoomId());
            assertEquals(MatchState.MATCH_RESULT, m.getState()); assertEquals(com.example.janken.domain.enums.MatchEndType.ABORTED, m.getEndType());
            assertFalse(m.getParticipants().get(u.getId()).isActive()); assertSame(old, u.getOriginalHand());
            assertEquals(operation.equals("logout"), host.isInvalid()); return;
        }
        error(409,"INVALID_STATE",action); assertFalse(host.isInvalid()); assertSame(u,user(host));
        assertSame(old,u.getOriginalHand()); assertEquals(UserState.PLAYING,u.getState());
        assertEquals(m.getId(),r.getCurrentMatchId()); assertEquals(1,matches.findAll().size());
        assertEquals(3,r.getTargetWins()); assertEquals(2,r.getMemberIds().size());
    }
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

    @ParameterizedTest @ValueSource(strings={"start","ready","cancel","rules","save","delete","participantSave","participantDelete","leave","logout"})
    void concurrentStartOperationsRemainConsistent(String operation) throws Exception {
        for(int i=0;i<30;i++) {
            setup(); var sessions=prepared(2,true); var host=sessions.getFirst(); var other=sessions.get(1);
            var extra=member("Extra","R","HE"); String id=id(host); Room r=room(host); var u=user(host); var original=u.getOriginalHand();
            var save=hand(extra,"Changed"); var delete=delete(extra); var participantSave=hand(host,"Changed"); var participantDelete=delete(host);
            Runnable action=switch(operation){
                case "start" -> ()->service.startMatch(host,id); case "ready" -> ()->service.ready(extra,id);
                case "cancel" -> ()->service.cancelReady(other,id); case "rules" -> ()->service.updateRules(host,rules(id,"5",true));
                case "save" -> ()->hands.save(extra,save); case "delete" -> ()->hands.delete(extra,delete);
            case "participantSave" -> ()->hands.save(host,participantSave); case "participantDelete" -> ()->hands.delete(host,participantDelete);
                case "leave" -> ()->service.leaveRoom(host,id); default -> ()->auth.logout(host);
            };
            var out=race(()->service.startMatch(host,id),action);
            assertTrue(matches.findAll().size()<=1);
            if(matches.findAll().isEmpty()) {
                assertNull(r.getCurrentMatchId()); assertFalse(users.findAll().stream().anyMatch(x->x.getState()==UserState.PLAYING));
                assertEquals("OK",out.get(1));
                assertTrue(List.of("INVALID_STATE","LOGIN_REQUIRED").contains(out.getFirst()));
                if(operation.equals("cancel")) { assertEquals(UserState.ROOM_WAITING,user(other).getState()); }
                else { assertNull(u.getCurrentRoomId()); assertFalse(r.getMemberIds().contains(u.getId())); assertEquals(user(other).getId(),r.getHostUserId()); }
            } else {
                var m=matches.findAll().getFirst();
                if (operation.equals("leave") || operation.equals("logout")) {
                    assertEquals(List.of("OK", "OK"), out);
                    assertEquals(MatchState.MATCH_RESULT, m.getState()); assertNull(r.getCurrentMatchId());
                    assertEquals(UserState.ROOM_NONE, u.getState()); assertEquals(UserState.ROOM_WAITING, user(other).getState());
                    assertFalse(m.getParticipants().get(u.getId()).isActive()); continue;
                }
                assertEquals(m.getId(),r.getCurrentMatchId());
                if (operation.equals("start")) {
                    assertEquals(1,Collections.frequency(out,"OK")); assertEquals(1,Collections.frequency(out,"INVALID_STATE"));
                } else { assertEquals("OK",out.getFirst()); }
                assertEquals(UserState.PLAYING,u.getState()); assertSame(original,u.getOriginalHand());
                m.getParticipants().keySet().forEach(uid->assertEquals(UserState.PLAYING,users.findById(uid).orElseThrow().getState()));
                if(operation.equals("rules")) {
                    assertEquals(r.getTargetWins(),m.getTargetWins()); assertEquals(r.isPreventConsecutiveSameOriginalHand(),m.isPreventConsecutiveSameOriginalHand());
                    assertEquals(out.get(1).equals("OK")?5:3,m.getTargetWins());
                } else if(operation.equals("ready")) {
                    assertEquals("OK",out.get(1));
                    assertEquals(m.getParticipants().containsKey(user(extra).getId())?UserState.PLAYING:UserState.READY,user(extra).getState());
                } else if(operation.equals("save")||operation.equals("delete")) {
                    assertEquals("OK",out.get(1)); assertFalse(m.getParticipants().containsKey(user(extra).getId()));
                } else if (!operation.equals("start")) { assertEquals("INVALID_STATE",out.get(1)); }
                assertTrue(m.getCurrentRound().getSelections().isEmpty()); if(operation.equals("ready")) { assertTrue(List.of(2,3).contains(m.getParticipants().size())); } else { assertEquals(2,m.getParticipants().size()); }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"leave","logout"})
    void waitingHostCanLeaveAfterStartWithoutChangingParticipants(String operation) throws Exception {
        for(int i=0;i<30;i++) {
            setup(); var sessions=prepared(2,false); var host=sessions.getFirst(); var hostUser=user(host);
            Room r=room(host); String id=id(host);
            var out=race(()->service.startMatch(host,id),()->{
                if(operation.equals("leave")) { service.leaveRoom(host,id); } else { auth.logout(host); }
            });
            assertEquals("OK",out.get(1)); assertEquals(UserState.ROOM_NONE,hostUser.getState());
            assertNull(hostUser.getCurrentRoomId()); assertFalse(r.getMemberIds().contains(hostUser.getId()));
            assertEquals(user(sessions.get(1)).getId(),r.getHostUserId());
            if(matches.findAll().isEmpty()) {
                assertNull(r.getCurrentMatchId()); assertTrue(List.of("INVALID_STATE","LOGIN_REQUIRED").contains(out.getFirst()));
                for(var participant:sessions.subList(1,sessions.size())) { assertEquals(UserState.READY,user(participant).getState()); }
            } else {
                assertEquals("OK",out.getFirst()); var m=matches.findAll().getFirst();
                assertEquals(m.getId(),r.getCurrentMatchId()); assertEquals(2,m.getParticipants().size());
                assertFalse(m.getParticipants().containsKey(hostUser.getId()));
                for(var participant:sessions.subList(1,sessions.size())) { assertEquals(UserState.PLAYING,user(participant).getState()); }
            }
            assertEquals(2,r.getMemberIds().size());
        }
    }


    @Test void participantOrderFollowsEntryRatherThanReadyOrder() {
        var host = member("Host", "R", "HH");
        var first = member("First", "R", "H1");
        var second = member("Second", "R", "H2");
        // 準備完了の順番を逆転させても、開始時の並びは入室順で固定する。
        service.ready(second, id(host));
        service.ready(first, id(host));
        GameMatch match = started(host);
        assertEquals(List.of(user(first).getId(), user(second).getId()),
                new ArrayList<>(match.getParticipants().keySet()));
        assertEquals(List.of("H1", "H2"), match.getOriginalHands().stream()
                .map(OriginalHandSnapshot::getName).toList());
        assertEquals(UserState.ROOM_WAITING, user(host).getState());
    }

    @Test void playingUserCannotCreateHandEvenWhenCurrentHandIsMissing() {
        var sessions = prepared(2, true);
        var host = sessions.getFirst();
        var form = hand(host, "New");
        GameMatch match = started(host);
        // 業務APIでは起こらない欠損を用意し、作成も状態判定で拒否することを確認する。
        user(host).setOriginalHand(null);
        error(409, "INVALID_STATE", () -> hands.save(host, form));
        assertNull(user(host).getOriginalHand());
        assertEquals("HH", match.getOriginalHands().getFirst().getName());
    }

    @ParameterizedTest
    @CsvSource({"start,true", "start,false", "ready,true", "ready,false",
            "cancel,true", "cancel,false", "rules,true", "rules,false",
            "leave,true", "leave,false", "logout,true", "logout,false"})
    void competingRequestsRespectBothLockOrders(String operation, boolean startFirst) throws Exception {
        var sessions = prepared(2, true);
        var host = sessions.getFirst();
        var other = sessions.get(1);
        var extra = member("Extra", "R", "HE");
        var hostUser = user(host);
        var otherUser = user(other);
        var extraUser = user(extra);
        Room r = room(host);
        String roomId = id(host);
        Runnable start = () -> service.startMatch(host, roomId);
        Runnable competing = switch (operation) {
            case "start" -> start;
            case "ready" -> () -> service.ready(extra, roomId);
            case "cancel" -> () -> service.cancelReady(other, roomId);
            case "rules" -> () -> service.updateRules(host, rules(roomId, "5", true));
            case "leave" -> () -> service.leaveRoom(host, roomId);
            default -> () -> auth.logout(host);
        };
        Runnable first = startFirst ? start : competing;
        Runnable second = startFirst ? competing : start;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var queuedThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        CountDownLatch attempted = new CountDownLatch(1);
        String firstResult;
        Future<String> secondResult;
        try {
            synchronized (lock) {
                secondResult = pool.submit(() -> {
                    queuedThread.set(Thread.currentThread());
                    attempted.countDown();
                    return operationResult(second);
                });
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                // 競合要求が同じロックで待つことを確認してから先行要求を実行する。
                // 単に同時スタートする試験に加え、両方の取得順を必ず検証する。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (queuedThread.get().getState() != Thread.State.BLOCKED
                        && !secondResult.isDone() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, queuedThread.get().getState());
                firstResult = operationResult(first);
                assertFalse(secondResult.isDone());
            }
            String followingResult = secondResult.get(5, TimeUnit.SECONDS);
            assertEquals("OK", firstResult);
            if (operation.equals("ready") || (startFirst && (operation.equals("leave") || operation.equals("logout")))) {
                assertEquals("OK", followingResult);
            } else if (!startFirst && operation.equals("rules")) {
                assertEquals("OK", followingResult);
            } else {
                assertEquals(!startFirst && operation.equals("logout")
                        ? "LOGIN_REQUIRED" : "INVALID_STATE", followingResult);
            }
            boolean started = startFirst || operation.equals("start")
                    || operation.equals("ready") || operation.equals("rules");
            assertEquals(started ? 1 : 0, matches.findAll().size());
            if (started) {
                GameMatch match = matches.findAll().getFirst();
                if (operation.equals("leave") || operation.equals("logout")) {
                    assertEquals(MatchState.MATCH_RESULT, match.getState()); assertNull(r.getCurrentMatchId());
                    assertEquals(UserState.ROOM_NONE, hostUser.getState()); assertEquals(UserState.ROOM_WAITING, otherUser.getState());
                    assertEquals(otherUser.getId(), r.getHostUserId()); assertEquals(operation.equals("logout"), host.isInvalid());
                    return;
                }
                assertEquals(match.getId(), r.getCurrentMatchId());
                assertEquals(UserState.PLAYING, hostUser.getState());
                assertEquals(UserState.PLAYING, otherUser.getState());
                boolean extraParticipates = operation.equals("ready") && !startFirst;
                assertEquals(extraParticipates ? 3 : 2, match.getParticipants().size());
                assertEquals(extraParticipates ? UserState.PLAYING
                        : operation.equals("ready") ? UserState.READY : UserState.ROOM_WAITING,
                        extraUser.getState());
                assertEquals(operation.equals("rules") && !startFirst ? 5 : 3, match.getTargetWins());
                assertEquals(r.getTargetWins(), match.getTargetWins());
                assertEquals(r.isPreventConsecutiveSameOriginalHand(), match.isPreventConsecutiveSameOriginalHand());
                assertTrue(match.getCurrentRound().getSelections().isEmpty());
            } else {
                assertNull(r.getCurrentMatchId());
                if (operation.equals("cancel")) {
                    assertEquals(UserState.READY, hostUser.getState());
                    assertEquals(UserState.ROOM_WAITING, otherUser.getState());
                } else {
                    assertEquals(UserState.READY, otherUser.getState());
                    assertEquals(UserState.ROOM_NONE, hostUser.getState());
                    assertNull(hostUser.getCurrentRoomId());
                    assertFalse(r.getMemberIds().contains(hostUser.getId()));
                    assertEquals(otherUser.getId(), r.getHostUserId());
                    assertEquals(operation.equals("logout"), host.isInvalid());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    String operationResult(Runnable action) {
        try { action.run(); return "OK"; }
        catch (GameOperationException e) { return e.getCode(); }
    }
}
