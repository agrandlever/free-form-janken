package com.example.janken.service;

import com.example.janken.domain.GameUser;
import com.example.janken.domain.Room;
import com.example.janken.domain.OriginalHand;
import com.example.janken.domain.enums.UserState;
import com.example.janken.domain.enums.HandRelation;
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

/** TC-001～012、104～106、116～117、122～123、128～129の第4段階部分。 */
class StageFourServiceTests {
    GameStateLock lock;
    UserStore users;
    RoomStore rooms;
    SessionUserAccess access;
    RoomService roomService;
    AuthService auth;
    TestClock clock;

    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = new RoomStore();
        access = new SessionUserAccess(users); clock = new TestClock();
        roomService = new RoomService(lock, rooms, access, clock, users, new MatchService(lock, new MatchStore(), clock, rooms, access, new RoundJudgeService(), users, new MatchResultService(lock, new com.example.janken.store.MatchResultStore(), access, rooms, new MatchStore())));
        auth = new AuthService(lock, users, access, roomService, clock);
    }

    MockHttpSession login(String name) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        auth.login(request, name);
        return (MockHttpSession) request.getSession(false);
    }
    GameUser user(MockHttpSession session) { synchronized(lock) { return access.require(session); } }
    Room room(MockHttpSession session) { synchronized(lock) { return rooms.findById(user(session).getCurrentRoomId()).orElseThrow(); } }
    void error(int status, String code, Runnable action) {
        GameOperationException ex = assertThrows(GameOperationException.class, action::run);
        assertEquals(status, ex.getStatus()); assertEquals(code, ex.getCode());
    }

    static Stream<String> validNames() { return Stream.of(" a ", "　\tあ　", "a".repeat(20), "😀", " 😀".repeat(1).strip(), "　" + "😀".repeat(20) + "\t"); }
    static Stream<String> invalidNames() { return Stream.of(null, "", " ", "\t　 ", "a".repeat(21), "😀".repeat(21)); }

    @ParameterizedTest @MethodSource("validNames") void validLoginAndRoomNames(String name) {
        MockHttpSession session = login(name); GameUser user = user(session);
        assertEquals(name.strip(), user.getUsername()); assertEquals(UserState.ROOM_NONE, user.getState());
        assertNull(user.getCurrentRoomId()); assertNull(user.getOriginalHand()); assertEquals(clock.now, user.getLastSeenAt());
        roomService.enterRoom(session, name); Room room = room(session);
        assertEquals(name.strip(), room.getName()); assertEquals(3, room.getTargetWins());
        assertFalse(room.isPreventConsecutiveSameOriginalHand()); assertNull(room.getCurrentMatchId()); assertNull(room.getLastCompletedMatchId());
        assertEquals(List.of(user.getId()), room.getMemberIds()); assertEquals(user.getId(), room.getHostUserId());
    }

    @ParameterizedTest @MethodSource("invalidNames") void invalidNamesHaveNoPartialUpdates(String name) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        error(400, "VALIDATION_ERROR", () -> auth.login(request, name));
        assertNull(request.getSession(false)); assertTrue(users.findAll().isEmpty());
        MockHttpSession session = login("A"); GameUser user = user(session); Instant before = user.getLastSeenAt();
        error(400, "VALIDATION_ERROR", () -> roomService.enterRoom(session, name));
        assertTrue(rooms.findAll().isEmpty()); assertEquals(UserState.ROOM_NONE, user.getState());
        assertNull(user.getCurrentRoomId()); assertNull(user.getJoinedRoomAt()); assertEquals(before, user.getLastSeenAt());
    }

    @Test void duplicateUsernameAllowedAndReloginPreservesEverything() {
        MockHttpSession a = login("same"), b = login("same");
        assertNotEquals(user(a).getId(), user(b).getId()); roomService.enterRoom(a, "room");
        GameUser original = user(a); UUID roomId = original.getCurrentRoomId();
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setSession(a);
        error(409, "INVALID_STATE", () -> auth.login(request, null));
        assertSame(original, user(a)); assertEquals(roomId, original.getCurrentRoomId()); assertEquals(2, users.findAll().size());
        assertEquals(original.getId(), a.getAttribute(SessionUserAccess.USER_ID));
    }

    @Test void normalizedRoomAndFixedEntryTime() {
        MockHttpSession a = login("A"), b = login("B");
        clock.now = clock.now.plusSeconds(100); roomService.enterRoom(a, "　共有\t ");
        Room room = room(a); roomService.enterRoom(b, "共有"); assertSame(room, room(b));
        assertEquals(1, rooms.findAll().size()); assertEquals(clock.now, user(a).getJoinedRoomAt());
        assertEquals(clock.now, user(a).getLastSeenAt()); assertEquals(clock.now, user(b).getLastSeenAt());
    }

    @ParameterizedTest @MethodSource("invalidNames") void statePrecedesInput(String name) {
        MockHttpSession a = login("A"); roomService.enterRoom(a, "R"); UUID id = user(a).getCurrentRoomId();
        error(409, "INVALID_STATE", () -> roomService.enterRoom(a, name));
        assertEquals(id, user(a).getCurrentRoomId()); assertEquals(1, rooms.findAll().size());
        MockHttpSession inconsistent = login("B"); user(inconsistent).setCurrentRoomId(id);
        error(409, "INVALID_STATE", () -> roomService.enterRoom(inconsistent, name));
    }

    @Test void ninthMemberRejectedWithoutChanges() {
        for (int i=0;i<8;i++) roomService.enterRoom(login("U"+i), "R");
        MockHttpSession ninth = login("9"); Room room = rooms.findByName("R").orElseThrow();
        List<UUID> ids = List.copyOf(room.getMemberIds());
        error(409, "ROOM_FULL", () -> roomService.enterRoom(ninth, " R "));
        assertEquals(ids, room.getMemberIds()); assertEquals(1, rooms.findAll().size());
        assertEquals(UserState.ROOM_NONE, user(ninth).getState()); assertNull(user(ninth).getCurrentRoomId());
    }

    @Test void leavePreservesLoginIdentityHandAndTimesAndTransfersInOrder() {
        MockHttpSession a = login("A"), b = login("B"), c = login("C");
        roomService.enterRoom(a,"R"); roomService.enterRoom(b,"R"); roomService.enterRoom(c,"R");
        GameUser original = user(a); Room room = room(a); Instant joined = original.getJoinedRoomAt(), seen = original.getLastSeenAt();
        OriginalHand hand = new OriginalHand(UUID.randomUUID(), "手", HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.DRAW);
        original.setOriginalHand(hand);
        roomService.leaveRoom(c, room.getId().toString()); assertEquals(original.getId(), room.getHostUserId());
        roomService.enterRoom(c,"R"); roomService.leaveRoom(a,room.getId().toString());
        assertEquals(user(b).getId(), room.getHostUserId()); assertSame(original, user(a)); assertSame(hand, original.getOriginalHand());
        assertEquals("A", original.getUsername()); assertNull(original.getCurrentRoomId()); assertEquals(UserState.ROOM_NONE, original.getState());
        assertEquals(joined, original.getJoinedRoomAt()); assertEquals(seen, original.getLastSeenAt());
        roomService.leaveRoom(b,room.getId().toString()); assertEquals(user(c).getId(),room.getHostUserId());
        roomService.leaveRoom(c,room.getId().toString()); assertTrue(rooms.findById(room.getId()).isEmpty()); assertTrue(rooms.findByName("R").isEmpty());
        roomService.enterRoom(a,"R"); assertNotEquals(room.getId(),room(a).getId()); assertSame(hand,user(a).getOriginalHand());
    }

    @Test void staleAndMalformedLeaveNeverChangeCurrentRoom() {
        MockHttpSession a=login("A"); roomService.enterRoom(a,"A"); UUID old=room(a).getId();
        roomService.leaveRoom(a,old.toString()); error(409,"INVALID_STATE",()->roomService.leaveRoom(a,old.toString()));
        roomService.enterRoom(a,"B"); Room current=room(a);
        error(409,"INVALID_STATE",()->roomService.leaveRoom(a,old.toString()));
        error(400,"VALIDATION_ERROR",()->roomService.leaveRoom(a,null));
        error(400,"VALIDATION_ERROR",()->roomService.leaveRoom(a,"bad"));
        assertSame(current,room(a)); assertEquals(List.of(user(a).getId()),current.getMemberIds());
    }

    @Test void logoutNoneAndWaitingNonHostHostAndLastMember() {
        MockHttpSession none=login("none"); UUID noneId=user(none).getId(); auth.logout(none); assertTrue(none.isInvalid()); assertTrue(users.findById(noneId).isEmpty());
        MockHttpSession a=login("A"), b=login("B"), c=login("C");
        roomService.enterRoom(a,"R"); roomService.enterRoom(b,"R"); roomService.enterRoom(c,"R"); Room room=room(a);
        UUID hostId=user(a).getId(); auth.logout(c); assertEquals(hostId,room.getHostUserId());
        auth.logout(a); assertEquals(user(b).getId(),room.getHostUserId()); assertEquals(UserState.ROOM_WAITING,user(b).getState());
        auth.logout(b); assertTrue(rooms.findByName("R").isEmpty()); assertTrue(rooms.findById(room.getId()).isEmpty()); assertTrue(users.findAll().isEmpty());
        error(401,"LOGIN_REQUIRED",()->auth.logout(a)); error(401,"LOGIN_REQUIRED",()->roomService.enterRoom(a,"R"));
    }

    List<String> together(Runnable first, Runnable second) throws Exception {
        CountDownLatch waiting=new CountDownLatch(2), start=new CountDownLatch(1);
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures=new ArrayList<>();
            for(Runnable action:List.of(first,second)) futures.add(pool.submit(()->{
                waiting.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { action.run(); return "OK"; } catch(GameOperationException ex) { return ex.getCode(); }
            }));
            assertTrue(waiting.await(5,TimeUnit.SECONDS)); start.countDown();
            return List.of(futures.get(0).get(5,TimeUnit.SECONDS),futures.get(1).get(5,TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(20) void concurrentSameNameCreatesOnlyOneRoom() throws Exception {
        MockHttpSession a=login("A"),b=login("B");
        assertEquals(List.of("OK","OK"),together(()->roomService.enterRoom(a,"R"),()->roomService.enterRoom(b," R ")));
        assertEquals(1,rooms.findAll().size()); assertSame(room(a),room(b)); assertEquals(2,room(a).getMemberIds().size());
    }
    @RepeatedTest(20) void concurrentEighthSlotOnlyOneSucceeds() throws Exception {
        for(int i=0;i<7;i++) roomService.enterRoom(login("U"+i),"R");
        MockHttpSession a=login("A"),b=login("B"); List<String> results=together(()->roomService.enterRoom(a,"R"),()->roomService.enterRoom(b,"R"));
        assertEquals(1,Collections.frequency(results,"OK")); assertEquals(1,Collections.frequency(results,"ROOM_FULL"));
        assertEquals(8,rooms.findByName("R").orElseThrow().getMemberIds().size());
        assertEquals(1,Stream.of(user(a),user(b)).filter(u->u.getState()==UserState.ROOM_NONE && u.getCurrentRoomId()==null).count());
    }
    @RepeatedTest(20) void sameUserConcurrentDifferentRoomsOnlyOneSucceeds() throws Exception {
        MockHttpSession a=login("A"); List<String> results=together(()->roomService.enterRoom(a,"R1"),()->roomService.enterRoom(a,"R2"));
        assertEquals(1,Collections.frequency(results,"OK")); assertEquals(1,Collections.frequency(results,"INVALID_STATE"));
        assertEquals(1,rooms.findAll().size()); assertEquals(List.of(user(a).getId()),room(a).getMemberIds());
    }
    @RepeatedTest(20) void lastLeaveVersusSameNameEntryPreservesIndex() throws Exception {
        MockHttpSession a=login("A"),b=login("B"); roomService.enterRoom(a,"R"); UUID old=room(a).getId();
        assertEquals(List.of("OK","OK"),together(()->roomService.leaveRoom(a,old.toString()),()->roomService.enterRoom(b,"R")));
        Room remaining=room(b); assertEquals(1,rooms.findAll().size()); assertSame(remaining,rooms.findByName("R").orElseThrow());
        assertSame(remaining,rooms.findById(remaining.getId()).orElseThrow()); assertEquals(List.of(user(b).getId()),remaining.getMemberIds());
        assertEquals(user(b).getId(),remaining.getHostUserId()); assertNull(user(a).getCurrentRoomId());
    }
    @Test void snapshotDoesNotChangeAfterDomainUpdates() {
        MockHttpSession a=login("A"); roomService.enterRoom(a,"R"); ScreenService service=new ScreenService(lock,access,rooms,users,new com.example.janken.store.MatchStore());
        ScreenService.Screen screen=service.current(a); roomService.leaveRoom(a,room(a).getId().toString());
        assertEquals("R",screen.model().get("roomName")); assertEquals(List.of(new ScreenService.MemberView("A",true,false,false)),screen.model().get("members"));
    }
}
