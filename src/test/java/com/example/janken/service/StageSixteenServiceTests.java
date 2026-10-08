package com.example.janken.service;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.form.HandSelectionForm;
import com.example.janken.scheduler.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** TC-064～067・117と通信監視に関わる競合を、実時間のsleepなしで確認する。 */
class StageSixteenServiceTests {
    static final Instant START = Instant.parse("2026-10-08T00:00:00Z");
    static class TestClock extends Clock {
        Instant now = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    GameStateLock lock; UserStore users; RoomStore rooms; MatchStore matches;
    MatchResultStore results; TestClock clock; SessionUserAccess access;
    RoomService roomService; MatchService service; AuthService auth; StatusService status;
    DisconnectMonitor monitor; MatchTransitionScheduler scheduler;
    List<GameUser> players; List<MockHttpSession> sessions; Room room; GameMatch match;

    @BeforeEach void setup() {
        lock = new GameStateLock(); users = new UserStore(); rooms = spy(new RoomStore());
        matches = new MatchStore(); results = spy(new MatchResultStore()); clock = new TestClock();
        access = new SessionUserAccess(users);
        var resultService = new MatchResultService(lock, results, access, rooms, matches);
        service = new MatchService(lock, matches, clock, rooms, access, new RoundJudgeService(), users, resultService);
        roomService = spy(new RoomService(lock, rooms, access, clock, users, service));
        auth = new AuthService(lock, users, access, roomService, clock);
        status = new StatusService(lock, access, users, rooms, matches, clock, results);
        monitor = new DisconnectMonitor(lock, users, roomService, clock);
        scheduler = new MatchTransitionScheduler(lock, matches, service, clock);
        players = new ArrayList<>(); sessions = new ArrayList<>();
    }
    MockHttpSession login(String name) {
        var request = new MockHttpServletRequest(); auth.login(request, name);
        return (MockHttpSession) request.getSession(false);
    }
    GameUser user(MockHttpSession s) { return access.require(s); }
    void join(int count) {
        for (int i = 0; i < count; i++) {
            var s = login("P" + i); var u = user(s);
            u.setOriginalHand(new OriginalHand(UUID.randomUUID(), "H" + i,
                    HandRelation.WIN, HandRelation.LOSE, HandRelation.DRAW, HandRelation.LOSE));
            roomService.enterRoom(s, "R"); players.add(u); sessions.add(s);
        }
        room = rooms.findById(players.getFirst().getCurrentRoomId()).orElseThrow();
    }
    void start(int count, int target) {
        join(count); room.setTargetWins(target);
        sessions.forEach(s -> roomService.ready(s, room.getId().toString()));
        var id = roomService.startMatch(sessions.getFirst(), room.getId().toString()).matchId();
        match = matches.findById(id).orElseThrow();
    }
    HandSelectionForm form(int i, NormalHandType hand) {
        var f = new HandSelectionForm(); f.setMatchId(match.getId().toString());
        f.setRoundNumber(Integer.toString(match.getCurrentRound().getRoundNumber()));
        f.setType("NORMAL"); f.setNormalHand(hand.name()); return f;
    }
    void submit(int i, NormalHandType hand) { service.submitHand(sessions.get(i), form(i, hand)); }
    void round(boolean winner) {
        for (int i = 0; i < players.size(); i++) submit(i, winner && i > 0 ? NormalHandType.SCISSORS : NormalHandType.ROCK);
    }
    void expire(int i) {
        players.forEach(u -> u.setLastSeenAt(clock.now));
        players.get(i).setLastSeenAt(clock.now.minusSeconds(30));
    }
    void checkIdentity(int i, OriginalHand hand, Instant lastSeen) {
        var u = players.get(i);
        assertSame(u, users.findById(u.getId()).orElseThrow()); assertSame(u, access.require(sessions.get(i)));
        assertEquals("P" + i, u.getUsername()); assertSame(hand, u.getOriginalHand());
        assertEquals(lastSeen, u.getLastSeenAt()); assertEquals(UserState.ROOM_NONE, u.getState());
        assertNull(u.getCurrentRoomId());
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,-1000000", "ROOM_WAITING,-1", "ROOM_WAITING,0", "ROOM_WAITING,1",
            "READY,-1000000", "READY,-1", "READY,0", "READY,1", "PLAYING,-1000000", "PLAYING,-1", "PLAYING,0", "PLAYING,1"})
    void tc064DeadlineBoundary(UserState state, long nanos) {
        if (state == UserState.PLAYING) start(3, 3); else { join(2); if (state == UserState.READY) roomService.ready(sessions.getFirst(), room.getId().toString()); }
        clock.now = START.plusSeconds(30).plusNanos(nanos);
        for (int i = 1; i < players.size(); i++) players.get(i).setLastSeenAt(clock.now);
        monitor.runChecks(); monitor.runChecks();
        assertEquals(nanos < 0 ? state : UserState.ROOM_NONE, players.getFirst().getState());
        assertEquals(nanos < 0, room.getMemberIds().contains(players.getFirst().getId()));
        assertEquals(START, players.getFirst().getLastSeenAt());
        if (state == UserState.PLAYING) { assertEquals(nanos < 0, match.getParticipants().get(players.getFirst().getId()).isActive()); assertTrue(results.findAll().isEmpty()); }
        verify(roomService, times(nanos < 0 ? 0 : 1)).leaveRoom(players.getFirst());
    }
    @ParameterizedTest @CsvSource({"30,false", "60,false", "600,false", "30,true", "60,true", "600,true"})
    void roomNoneNeverExpires(long seconds, boolean hasHand) {
        var s = login("A"); var u = user(s);
        if (hasHand) u.setOriginalHand(new OriginalHand(UUID.randomUUID(), "HA", HandRelation.LOSE, HandRelation.WIN, HandRelation.DRAW, HandRelation.LOSE));
        var hand = u.getOriginalHand(); clock.now = START.plusSeconds(seconds); monitor.runChecks();
        assertSame(u, access.require(s)); assertEquals(UserState.ROOM_NONE, u.getState()); assertSame(hand, u.getOriginalHand());
        assertEquals(START, u.getLastSeenAt()); assertTrue(rooms.findAll().isEmpty()); verify(roomService, never()).leaveRoom(any(GameUser.class));
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,true", "ROOM_WAITING,false", "READY,true", "READY,false"})
    void tc065RoomTimeoutTransfersOldestAndKeepsLoginAndHand(UserState state, boolean host) {
        join(3); int i = host ? 0 : 1;
        if (state == UserState.READY) roomService.ready(sessions.get(i), room.getId().toString());
        var hand = players.get(i).getOriginalHand(); expire(i); var lastSeen = players.get(i).getLastSeenAt();
        monitor.runChecks(); monitor.runChecks(); checkIdentity(i, hand, lastSeen);
        assertEquals(host ? players.get(1).getId() : players.get(0).getId(), room.getHostUserId());
        assertEquals(2, room.getMemberIds().size()); assertTrue(matches.findAll().isEmpty());
        verify(roomService, times(1)).leaveRoom(players.get(i)); verify(rooms, never()).deleteById(any());
        var view = status.status(sessions.get(i), null); assertEquals(UserState.ROOM_NONE, view.userState()); assertNull(view.room());
    }
    @ParameterizedTest @EnumSource(value=UserState.class, names={"ROOM_WAITING", "READY"})
    void lastMemberTimeoutDeletesRoomAndAllowsNameReuse(UserState state) {
        join(1); if (state == UserState.READY) roomService.ready(sessions.getFirst(), room.getId().toString());
        expire(0); monitor.runChecks(); monitor.runChecks();
        assertTrue(rooms.findAll().isEmpty()); assertTrue(rooms.findByName("R").isEmpty());
        verify(rooms, times(1)).deleteById(room.getId());
        roomService.enterRoom(sessions.getFirst(), "R"); assertNotEquals(room.getId(), players.getFirst().getCurrentRoomId());
    }
    @Test void readyTimeoutReleasesNamesAndCannotBecomeMatchParticipant() {
        join(3); var first = players.get(0); var second = players.get(1);
        var sameName = new GameUser(second.getId(), first.getUsername(), START);
        sameName.setOriginalHand(second.getOriginalHand()); sameName.setState(second.getState()); sameName.setCurrentRoomId(second.getCurrentRoomId());
        users.save(sameName); players.set(1, sameName); first.getOriginalHand().setName(second.getOriginalHand().getName());
        roomService.ready(sessions.getFirst(), room.getId().toString()); expire(0); monitor.runChecks();
        roomService.ready(sessions.get(1), room.getId().toString()); roomService.ready(sessions.get(2), room.getId().toString());
        var id = roomService.startMatch(sessions.get(1), room.getId().toString()).matchId();
        assertFalse(matches.findById(id).orElseThrow().getParticipants().containsKey(first.getId()));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tc066SelectingRemovesOnlyCurrentSelectionAndWaits(boolean submitted) {
        start(3, 3); round(true); clock.now = match.getTransitionAt(); scheduler.runTransitions();
        if (submitted) submit(0, NormalHandType.PAPER);
        submit(1, NormalHandType.ROCK); var oldHistory = List.copyOf(match.getRoundHistory());
        var participant = match.getParticipants().get(players.getFirst().getId()); var previous = participant.getPreviousHand();
        var hand = players.getFirst().getOriginalHand(); expire(0); var lastSeen = players.getFirst().getLastSeenAt();
        monitor.runChecks(); monitor.runChecks(); checkIdentity(0, hand, lastSeen);
        assertFalse(participant.isActive()); assertEquals(1, participant.getScore()); assertSame(previous, participant.getPreviousHand());
        assertEquals(oldHistory, match.getRoundHistory()); assertEquals(MatchState.SELECTING_HAND, match.getState());
        assertFalse(match.getCurrentRound().getSelections().containsKey(participant.getUserId())); assertEquals(1, match.getCurrentRound().getSelections().size());
        assertEquals(3, match.getOriginalHands().size()); assertTrue(results.findAll().isEmpty());
        // 退出した作成者の固定手も、残存参加者は引き続き利用できる。
        var f = form(2, NormalHandType.ROCK); f.setType("ORIGINAL"); f.setNormalHand(null); f.setOriginalHandId(hand.getId().toString());
        service.submitHand(sessions.get(2), f); assertEquals(MatchState.ROUND_RESULT, match.getState());
    }
    @Test void tc066UnconfirmedTimeoutCompletesRemainingRoundExactlyOnce() {
        start(3, 3); submit(1, NormalHandType.ROCK); submit(2, NormalHandType.SCISSORS); expire(0);
        monitor.runChecks(); monitor.runChecks();
        assertEquals(MatchState.ROUND_RESULT, match.getState()); assertEquals(1, match.getRoundHistory().size());
        assertEquals(1, match.getParticipants().get(players.get(1).getId()).getScore());
        assertEquals(2, match.getCurrentRound().getSelections().size()); assertTrue(results.findAll().isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tc066SelectingOneRemainingAbortsWithoutForfeit(boolean submitted) {
        start(2, 3); if (submitted) submit(0, NormalHandType.ROCK); expire(0); monitor.runChecks(); monitor.runChecks();
        assertEquals(MatchEndType.ABORTED, match.getEndType()); assertTrue(match.getWinnerIds().isEmpty());
        assertEquals(UserState.ROOM_WAITING, players.get(1).getState()); assertEquals(0, match.getParticipants().get(players.get(1).getId()).getScore());
        assertTrue(match.getRoundHistory().isEmpty()); assertFalse(match.getCurrentRound().getSelections().containsKey(players.get(0).getId()));
        verify(results, times(1)).save(any(MatchResultSnapshot.class));
    }
    @ParameterizedTest @CsvSource({"2,true", "2,false", "3,true", "3,false"})
    void tc066UndecidedResultPreservesAllConfirmedData(int count, boolean winner) {
        start(count, 3); round(winner); var round = match.getCurrentRound(); var deadline = match.getTransitionAt();
        var history = List.copyOf(match.getRoundHistory()); var scores = match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        var previous = match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList();
        expire(0); monitor.runChecks(); monitor.runChecks();
        assertSame(round, match.getCurrentRound()); assertEquals(history, match.getRoundHistory()); assertEquals(count, round.getSelections().size());
        assertEquals(scores, match.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
        assertEquals(previous, match.getParticipants().values().stream().map(MatchParticipant::getPreviousHand).toList());
        if (count == 2) { assertEquals(MatchEndType.ABORTED, match.getEndType()); assertTrue(match.getWinnerIds().isEmpty()); verify(results, times(1)).save(any(MatchResultSnapshot.class)); }
        else { assertEquals(deadline, match.getTransitionAt()); assertEquals(MatchState.ROUND_RESULT, match.getState());
            clock.now = deadline; scheduler.runTransitions(); assertEquals(2, match.getCurrentRound().getRoundNumber()); assertFalse(match.getParticipants().get(players.get(0).getId()).isActive()); }
    }
    @ParameterizedTest @ValueSource(strings={"winner", "loser", "everyone"})
    void tc067NormalProtectedIncludingDeletedRoomAndSameNameReplacement(String mode) {
        start(3, 1); round(true); var deadline = match.getTransitionAt(); var oldRound = match.getCurrentRound();
        var winners = List.copyOf(match.getPendingWinnerIds()); var history = List.copyOf(match.getRoundHistory());
        var scores = match.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        if (mode.equals("everyone")) players.forEach(u -> u.setLastSeenAt(clock.now.minusSeconds(30))); else expire(mode.equals("winner") ? 0 : 1);
        monitor.runChecks(); monitor.runChecks();
        assertEquals(MatchEndType.NORMAL, match.getPendingEndType()); assertNull(match.getEndType());
        assertEquals(MatchState.ROUND_RESULT, match.getState()); assertEquals(winners, match.getPendingWinnerIds());
        assertEquals(deadline, match.getTransitionAt()); assertSame(oldRound, match.getCurrentRound()); assertEquals(history, match.getRoundHistory());
        assertEquals(scores, match.getParticipants().values().stream().map(MatchParticipant::getScore).toList()); assertTrue(results.findAll().isEmpty());
        Room replacement = null;
        if (mode.equals("everyone")) {
            assertTrue(rooms.findAll().isEmpty()); verify(rooms, times(1)).deleteById(room.getId());
            var s = login("new"); roomService.enterRoom(s, "R"); replacement = rooms.findByName("R").orElseThrow();
            replacement.setTargetWins(9); replacement.setCurrentMatchId(UUID.randomUUID()); replacement.setLastCompletedMatchId(UUID.randomUUID());
        }
        var newMatch = replacement == null ? null : replacement.getCurrentMatchId(); var newLast = replacement == null ? null : replacement.getLastCompletedMatchId();
        clock.now = deadline.minusNanos(1); scheduler.runTransitions(); assertTrue(results.findAll().isEmpty());
        clock.now = deadline; scheduler.runTransitions(); scheduler.runTransitions();
        var snapshot = results.findById(match.getId()).orElseThrow(); assertEquals(MatchEndType.NORMAL, snapshot.getEndType()); assertEquals(winners, snapshot.getWinnerIds());
        assertEquals(3, snapshot.getParticipantNames().size()); assertEquals(3, snapshot.getOriginalHandAffinities().size()); assertEquals(history.stream().map(RoundResult::getRoundNumber).toList(), snapshot.getRoundHistory().stream().map(RoundResult::getRoundNumber).toList());
        verify(results, times(1)).save(any(MatchResultSnapshot.class));
        if (replacement != null) { assertEquals(newMatch, replacement.getCurrentMatchId()); assertEquals(newLast, replacement.getLastCompletedMatchId()); assertEquals(9, replacement.getTargetWins()); assertEquals(1, replacement.getMemberIds().size()); }
    }
    @ParameterizedTest @EnumSource(value=UserState.class, names={"ROOM_WAITING", "READY"})
    void spectatorTimeoutNeverTouchesMatch(UserState state) {
        start(3, 3); submit(0, NormalHandType.ROCK); var s = login("spectator"); roomService.enterRoom(s, "R");
        var u = user(s); u.setOriginalHand(new OriginalHand(UUID.randomUUID(), "spectatorHand", HandRelation.LOSE, HandRelation.WIN, HandRelation.DRAW, HandRelation.LOSE));
        if (state == UserState.READY) roomService.ready(s, room.getId().toString());
        var selections = Map.copyOf(match.getCurrentRound().getSelections()); var fixed = List.copyOf(match.getOriginalHands());
        u.setLastSeenAt(START.minusSeconds(30)); monitor.runChecks();
        assertEquals(UserState.ROOM_NONE, u.getState()); assertSame(u, access.require(s)); assertEquals(selections, match.getCurrentRound().getSelections());
        assertEquals(fixed, match.getOriginalHands()); assertEquals(3, match.getParticipants().size());
        assertTrue(match.getParticipants().values().stream().allMatch(p -> p.isActive() && p.getScore() == 0)); assertEquals(MatchState.SELECTING_HAND, match.getState());
    }
    @Test void tc117LongRoomNoneWaitThenEntryAndStatusResetDeadline() {
        var s = login("A"); clock.now = START.plusSeconds(600); monitor.runChecks();
        roomService.enterRoom(s, "R"); var u = user(s); assertEquals(clock.now, u.getLastSeenAt()); assertEquals(clock.now, u.getJoinedRoomAt());
        clock.now = clock.now.plusSeconds(30).minusNanos(1); monitor.runChecks(); assertEquals(UserState.ROOM_WAITING, u.getState());
        status.status(s, null); var heartbeat = clock.now; clock.now = heartbeat.plusSeconds(30).minusNanos(1); monitor.runChecks(); assertEquals(UserState.ROOM_WAITING, u.getState());
        clock.now = heartbeat.plusSeconds(30); monitor.runChecks(); assertEquals(UserState.ROOM_NONE, u.getState()); assertEquals(heartbeat, u.getLastSeenAt());
    }
    @ParameterizedTest @EnumSource(value=UserState.class, names={"ROOM_WAITING", "READY", "PLAYING"})
    void pollingContinuesForMinutesThenStops(UserState state) {
        if (state == UserState.PLAYING) start(3, 3); else { join(2); if (state == UserState.READY) roomService.ready(sessions.getFirst(), room.getId().toString()); }
        for (int second = 2; second <= 120; second += 2) {
            clock.now = START.plusSeconds(second); sessions.forEach(s -> status.status(s, match == null ? null : match.getId().toString()));
            monitor.runChecks(); assertEquals(state, players.getFirst().getState());
        }
        clock.now = START.plusSeconds(150).minusNanos(1); monitor.runChecks(); assertEquals(state, players.getFirst().getState());
        clock.now = START.plusSeconds(150); monitor.runChecks(); assertTrue(players.stream().allMatch(u -> u.getState() == UserState.ROOM_NONE)); assertTrue(rooms.findAll().isEmpty());
        assertEquals(UserState.ROOM_NONE, status.status(sessions.getFirst(), null).userState());
        if (match != null) { assertEquals(MatchEndType.ABORTED, match.getEndType()); verify(results, times(1)).save(any(MatchResultSnapshot.class)); }
    }
    @Test void failedStatusIsNotAHeartbeat() {
        join(2); clock.now = START.plusSeconds(30);
        assertEquals(400, assertThrows(GameOperationException.class, () -> status.status(sessions.getFirst(), "bad")).getStatus());
        assertEquals(401, assertThrows(GameOperationException.class, () -> status.status(new MockHttpSession(), null)).getStatus());
        assertEquals(START, players.getFirst().getLastSeenAt()); monitor.runChecks(); assertEquals(UserState.ROOM_NONE, players.getFirst().getState());
    }
    @Test void collectionClockChecksAndExitAllHoldSameLock() {
        join(2); expire(0); var store = spy(users); var time = mock(Clock.class);
        doAnswer(c -> { assertTrue(Thread.holdsLock(lock)); return c.callRealMethod(); }).when(store).findAll();
        when(time.instant()).thenAnswer(c -> { assertTrue(Thread.holdsLock(lock)); return clock.now; });
        doAnswer(c -> { assertTrue(Thread.holdsLock(lock)); return c.callRealMethod(); }).when(roomService).leaveRoom(any(GameUser.class));
        new DisconnectMonitor(lock, store, roomService, time).runChecks();
        verify(store, times(1)).findAll(); verify(roomService, times(1)).leaveRoom(players.getFirst());
    }
    @Test void laterUsersUseLatestHeartbeatAndStateAfterEarlierExit() {
        join(3); players.forEach(u -> u.setLastSeenAt(START.minusSeconds(30))); var store = mock(UserStore.class);
        when(store.findAll()).thenReturn(List.copyOf(players));
        doAnswer(c -> { var value = c.callRealMethod(); status.status(sessions.get(1), null); return value; }).when(roomService).leaveRoom(players.getFirst());
        new DisconnectMonitor(lock, store, roomService, clock).runChecks();
        assertEquals(UserState.ROOM_NONE, players.get(0).getState()); assertEquals(UserState.ROOM_WAITING, players.get(1).getState());
        assertEquals(UserState.ROOM_NONE, players.get(2).getState()); assertEquals(List.of(players.get(1).getId()), room.getMemberIds());
        assertEquals(players.get(1).getId(), room.getHostUserId()); verify(roomService, never()).leaveRoom(players.get(1));
    }
    String outcome(Runnable action) { try { action.run(); return "OK"; } catch (GameOperationException e) { return e.getCode(); } }
    // 後続スレッドが共有ロックでBLOCKEDになったことを確認してから先行操作を実行する。
    List<String> ordered(Runnable first, Runnable second) throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var ref = new AtomicReference<Thread>(); var entered = new CountDownLatch(1);
        try { Future<String> future; String firstResult;
            synchronized (lock) {
                future = pool.submit(() -> { ref.set(Thread.currentThread()); entered.countDown(); return outcome(second); });
                assertTrue(entered.await(5, TimeUnit.SECONDS)); long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ref.get().getState() != Thread.State.BLOCKED && System.nanoTime() < limit) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED, ref.get().getState()); firstResult = outcome(first); assertFalse(future.isDone());
            }
            return List.of(firstResult, future.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void statusVsMonitorBothLockOrders(boolean monitorFirst) throws Exception {
        join(2); expire(0); Runnable poll = () -> status.status(sessions.getFirst(), null);
        assertEquals(List.of("OK", "OK"), ordered(monitorFirst ? monitor::runChecks : poll, monitorFirst ? poll : monitor::runChecks));
        assertEquals(monitorFirst ? UserState.ROOM_NONE : UserState.ROOM_WAITING, players.getFirst().getState());
        assertEquals(clock.now, players.getFirst().getLastSeenAt()); assertSame(players.getFirst(), access.require(sessions.getFirst()));
        assertEquals(!monitorFirst, room.getMemberIds().contains(players.getFirst().getId()));
        verify(roomService, times(monitorFirst ? 1 : 0)).leaveRoom(players.getFirst());
    }
    @ParameterizedTest @CsvSource({"leave,true", "leave,false", "logout,true", "logout,false"})
    void monitorVsDepartureBothLockOrders(String operation, boolean monitorFirst) throws Exception {
        start(2, 3); expire(0); var id = players.getFirst().getId();
        Runnable depart = operation.equals("leave") ? () -> roomService.leaveRoom(sessions.getFirst(), room.getId().toString()) : () -> auth.logout(sessions.getFirst());
        var outcomes = ordered(monitorFirst ? monitor::runChecks : depart, monitorFirst ? depart : monitor::runChecks);
        assertEquals(List.of("OK", monitorFirst && operation.equals("leave") ? "INVALID_STATE" : "OK"), outcomes);
        assertEquals(MatchEndType.ABORTED, match.getEndType()); assertEquals(players.get(1).getId(), room.getHostUserId()); assertEquals(1, room.getMemberIds().size());
        verify(roomService, times(1)).leaveRoom(players.getFirst()); verify(results, times(1)).save(any(MatchResultSnapshot.class));
        if (operation.equals("logout")) { assertTrue(users.findById(id).isEmpty()); assertTrue(sessions.getFirst().isInvalid()); }
        else assertSame(players.getFirst(), access.require(sessions.getFirst()));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void monitorVsTransitionNormalOnceInBothOrders(boolean monitorFirst) throws Exception {
        start(2, 1); round(true); clock.now = match.getTransitionAt(); players.forEach(u -> u.setLastSeenAt(clock.now.minusSeconds(30)));
        var winner = players.getFirst().getId();
        assertEquals(List.of("OK", "OK"), ordered(monitorFirst ? monitor::runChecks : scheduler::runTransitions, monitorFirst ? scheduler::runTransitions : monitor::runChecks));
        assertTrue(rooms.findAll().isEmpty()); assertEquals(MatchEndType.NORMAL, match.getEndType());
        assertEquals(List.of(winner), results.findById(match.getId()).orElseThrow().getWinnerIds()); verify(results, times(1)).save(any(MatchResultSnapshot.class));
        verify(rooms, times(1)).deleteById(room.getId());
    }
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void monitorVsSubmitConsistentInBothOrders(boolean monitorFirst, boolean lastSubmit) throws Exception {
        start(3, 3); if (lastSubmit) { submit(1, NormalHandType.SCISSORS); submit(2, NormalHandType.SCISSORS); }
        expire(0); Runnable post = () -> submit(0, NormalHandType.ROCK);
        var outcome = ordered(monitorFirst ? monitor::runChecks : post, monitorFirst ? post : monitor::runChecks);
        assertEquals(List.of("OK", monitorFirst ? "INVALID_STATE" : "OK"), outcome);
        assertEquals(UserState.ROOM_NONE, players.getFirst().getState()); assertFalse(match.getParticipants().get(players.getFirst().getId()).isActive());
        if (lastSubmit) { assertEquals(MatchState.ROUND_RESULT, match.getState()); assertEquals(1, match.getRoundHistory().size());
            assertEquals(monitorFirst ? 0 : 1, match.getParticipants().get(players.getFirst().getId()).getScore()); }
        else { assertEquals(MatchState.SELECTING_HAND, match.getState()); assertTrue(match.getCurrentRound().getSelections().isEmpty()); }
        assertEquals("INVALID_STATE", outcome(post)); assertTrue(results.findAll().isEmpty());
    }
    @Test void concurrentMonitorsExitAndAbortAndDeleteOnlyOnce() throws Exception {
        start(3, 3); players.forEach(u -> u.setLastSeenAt(START.minusSeconds(30)));
        assertEquals(List.of("OK", "OK"), ordered(monitor::runChecks, monitor::runChecks)); monitor.runChecks();
        assertTrue(rooms.findAll().isEmpty()); assertTrue(players.stream().allMatch(u -> u.getState() == UserState.ROOM_NONE));
        players.forEach(u -> verify(roomService, times(1)).leaveRoom(u)); verify(rooms, times(1)).deleteById(room.getId());
        verify(results, times(1)).save(any(MatchResultSnapshot.class)); assertEquals(MatchEndType.ABORTED, match.getEndType());
    }
}