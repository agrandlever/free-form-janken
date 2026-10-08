package com.example.janken.controller;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.service.*;
import com.example.janken.store.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageFifteenWebTests {
    static class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary TestClock testClock(){return new TestClock();}
    }
    @Autowired TestClock clock;
    @LocalServerPort int port;
    @Autowired AuthController auth;
    @Autowired RoomController roomController;
    @Autowired GameController game;
    @Autowired OriginalHandController hands;
    @Autowired StatusApiController api;
    @Autowired HtmlErrorHandler errors;
    @Autowired GameStateLock lock;
    @Autowired UserStore users;
    @Autowired RoomStore rooms;
    @Autowired MatchStore matches;
    @Autowired MatchResultStore results;
    @Autowired MatchService service;
    MockMvc mvc; MockHttpSession a,b,c; UUID roomId,matchId;
    @BeforeEach void setup(){
        synchronized(lock){users.findAll().forEach(u->users.deleteById(u.getId()));rooms.findAll().forEach(r->rooms.deleteById(r.getId()));
            matches.findAll().forEach(m->matches.deleteById(m.getId()));results.findAll().forEach(r->results.deleteById(r.getMatchId()));
            clock.now=Instant.parse("2026-10-07T00:00:00Z");}
        mvc=MockMvcBuilders.standaloneSetup(auth,roomController,game,hands,api).setControllerAdvice(errors)
            .setViewResolvers((name,locale)->name.startsWith("redirect:")?new RedirectView(name.substring(9)):new View(){
                public void render(Map<String,?> model,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){}
            }).build();
    }
    MockHttpSession login(String name,String room) throws Exception {
        var s=(MockHttpSession)mvc.perform(post("/login").param("username",name)).andReturn().getRequest().getSession(false);
        if(room!=null)mvc.perform(post("/rooms/enter").session(s).param("roomName",room)).andExpect(status().isFound());return s;
    }
    GameUser user(MockHttpSession s){return users.findById((UUID)s.getAttribute(SessionUserAccess.USER_ID)).orElseThrow();}
    void ready(MockHttpSession s,String name) throws Exception {
        String rid=user(s).getCurrentRoomId().toString();
        mvc.perform(post("/original-hand/save").session(s).param("returnPage","ROOM").param("roomId",rid).param("name",name)
            .param("vsRock","WIN").param("vsScissors","LOSE").param("vsPaper","DRAW").param("vsOriginal","LOSE")).andExpect(status().isFound());
        mvc.perform(post("/room/ready").session(s).param("roomId",rid)).andExpect(status().isFound());
    }
    void start(int target,boolean third) throws Exception {
        a=login("Alice","R");b=login("Bob","R");if(third)c=login("Carol","R");roomId=user(a).getCurrentRoomId();
        mvc.perform(post("/room/rules").session(a).param("roomId",roomId.toString()).param("targetWins",Integer.toString(target)));
        ready(a,"HA");ready(b,"HB");if(third)ready(c,"HC");
        mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString())).andExpect(status().isFound());
        synchronized(lock){matchId=rooms.findById(roomId).orElseThrow().getCurrentMatchId();}
    }
    void submit(MockHttpSession s,String hand) throws Exception {
        mvc.perform(post("/play").session(s).param("matchId",matchId.toString()).param("roundNumber","1").param("type","NORMAL").param("normalHand",hand)).andExpect(status().isFound());
    }
    void normal() throws Exception {start(1,false);submit(a,"ROCK");submit(b,"SCISSORS");synchronized(lock){clock.now=matches.findById(matchId).orElseThrow().getTransitionAt();}service.advanceMatch(matchId);}


    @Autowired ScreenService screens;
    @Autowired StatusService statuses;
    @Autowired RoomService roomService;
    void transition() { new com.example.janken.scheduler.MatchTransitionScheduler(lock,matches,service,clock).runTransitions(); }
    MockHttpSession spectator(UserState state) throws Exception {
        var s=login(state==UserState.READY?"D":"C","R");
        if(state==UserState.READY)ready(s,"HD");return s;
    }
    void assertSpectator(MockHttpSession s,UserState state) {
        synchronized(lock){assertEquals(state,user(s).getState());assertEquals(roomId,user(s).getCurrentRoomId());
            assertFalse(matches.findById(matchId).orElseThrow().getParticipants().containsKey(user(s).getId()));}
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,SELECTING_HAND,play","READY,SELECTING_HAND,play","ROOM_WAITING,ROUND_RESULT,play","READY,ROUND_RESULT,play","ROOM_WAITING,MATCH_RESULT,play","READY,MATCH_RESULT,play","ROOM_WAITING,SELECTING_HAND,round-result","READY,SELECTING_HAND,round-result","ROOM_WAITING,ROUND_RESULT,round-result","READY,ROUND_RESULT,round-result","ROOM_WAITING,MATCH_RESULT,round-result","READY,MATCH_RESULT,round-result"})
    void directGetStateMatrix(UserState state,MatchState matchState,String requested) throws Exception {
        start(1,false);var s=spectator(state);
        if(matchState!=MatchState.SELECTING_HAND){submit(a,"ROCK");submit(b,"SCISSORS");}
        if(matchState==MatchState.MATCH_RESULT){synchronized(lock){clock.now=matches.findById(matchId).orElseThrow().getTransitionAt();}transition();}
        String expected=matchState==MatchState.SELECTING_HAND?"play":matchState==MatchState.ROUND_RESULT?"round-result":"match-result";
        var response=mvc.perform(get("/"+requested).session(s).param("matchId",matchId.toString()));
        if(requested.equals(expected))response.andExpect(status().isOk()).andExpect(view().name(expected))
            .andExpect(model().attribute("matchParticipant",false)).andExpect(model().attribute("userState",state))
            .andExpect(model().attribute("matchId",matchId.toString())).andExpect(model().attributeExists("roundHistory"));
        else response.andExpect(status().isFound()).andExpect(redirectedUrl("/"+expected+"?matchId="+matchId));
        assertSpectator(s,state);
        var status=statuses.status(s,matchId.toString());assertEquals(matchId,status.displayMatchId());assertEquals(matchState,status.displayMatchState());
        assertNull(status.selfHandConfirmed());assertNull(status.selfSelectedHand());
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,play","READY,play","ROOM_WAITING,round-result","READY,round-result"})
    void omittedIdResolvesCurrentButNoMatchReturnsRoom(UserState state,String page) throws Exception {
        start(3,false);var s=spectator(state);
        var r=mvc.perform(get("/"+page).session(s));
        if(page.equals("play"))r.andExpect(status().isOk()).andExpect(model().attribute("matchId",matchId.toString()));
        else r.andExpect(redirectedUrl("/play?matchId="+matchId));
        service.abortMatch(matchId);mvc.perform(get("/"+page).session(s)).andExpect(redirectedUrl("/room"));assertSpectator(s,state);
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,play","READY,play","ROOM_WAITING,round-result","READY,round-result"})
    void invalidAndOtherRoomTargetsNeverLeak(UserState state,String page) throws Exception {
        start(3,false);var s=spectator(state);UUID foreign=UUID.randomUUID();
        synchronized(lock){var m=new GameMatch(foreign,UUID.randomUUID(),"SECRET",99,false);m.setCurrentRound(new Round(99,clock.instant()));matches.save(m);}
        for(String id:List.of("bad","","1-1-1-1-1",UUID.randomUUID().toString(),foreign.toString())){
            mvc.perform(get("/"+page).session(s).param("matchId",id)).andExpect(status().isFound()).andExpect(redirectedUrl("/room"));
        }
        var response=statuses.status(s,foreign.toString());assertNull(response.displayMatchId());assertNull(response.displayMatchState());
        assertNull(response.displayRoundNumber());assertNull(response.displayTransitionAt());assertNull(response.selfHandConfirmed());assertNull(response.selfSelectedHand());
        assertSpectator(s,state);
    }
    @ParameterizedTest @CsvSource({"ROOM_WAITING,NORMAL","READY,NORMAL","ROOM_WAITING,ORIGINAL","READY,ORIGINAL"})
    void tc058DirectPostLeavesAllMatchValuesUnchanged(UserState state,String type) throws Exception {
        start(3,false);var s=spectator(state);var m=matches.findById(matchId).orElseThrow();
        var participants=Map.copyOf(m.getParticipants());var hands=List.copyOf(m.getOriginalHands());var scores=m.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        mvc.perform(post("/play").session(s).param("matchId",matchId.toString()).param("roundNumber","1")
            .param("type",type).param("normalHand","ROCK").param("originalHandId",m.getOriginalHands().getFirst().getHandId().toString()))
            .andExpect(status().isConflict()).andExpect(model().attribute("errorCode","INVALID_STATE"));
        synchronized(lock){assertEquals(participants,m.getParticipants());assertEquals(hands,m.getOriginalHands());assertEquals(scores,m.getParticipants().values().stream().map(MatchParticipant::getScore).toList());
            assertEquals(MatchState.SELECTING_HAND,m.getState());assertTrue(m.getCurrentRound().getSelections().isEmpty());assertTrue(m.getRoundHistory().isEmpty());assertEquals(3,m.getTargetWins());}
        assertSpectator(s,state);
    }
    @ParameterizedTest @EnumSource(value=UserState.class,names={"ROOM_WAITING","READY"})
    void tc056057084SafeCopiedModelAndReturn(UserState state) throws Exception {
        start(3,false);var m=matches.findById(matchId).orElseThrow();var participantIds=Set.copyOf(m.getParticipants().keySet());var hands=List.copyOf(m.getOriginalHands());
        var s=spectator(state);submit(a,"ROCK");var screen=screens.gamePage(s,matchId.toString());var model=screen.model();
        assertEquals("play",screen.template());assertEquals(false,model.get("matchParticipant"));assertTrue(model.containsKey("selectedHand"));assertNull(model.get("selectedHand"));
        for(String key:List.of("originalHandOptions","originalHandNames","availableHands","previousHand","hasConsecutiveUseRestriction","roomActionForm","score"))assertFalse(model.containsKey(key),key);
        assertEquals(2,((List<?>)model.get("participants")).size());assertTrue(((List<?>)model.get("roundHistory")).isEmpty());
        assertTrue(model.values().stream().noneMatch(v->v instanceof GameMatch||v instanceof MatchParticipant||v instanceof OriginalHandSnapshot));
        assertThrows(UnsupportedOperationException.class,()->model.put("oops",true));
        var status=statuses.status(s,matchId.toString());assertNull(status.selfHandConfirmed());assertNull(status.selfSelectedHand());assertEquals(clock.instant(),user(s).getLastSeenAt());
        mvc.perform(get("/room").session(s)).andExpect(status().isOk()).andExpect(model().attribute("currentMatchId",matchId)).andExpect(model().attribute("matchRunning",true));
        synchronized(lock){assertEquals(participantIds,m.getParticipants().keySet());assertEquals(hands,m.getOriginalHands());m.getParticipants().values().forEach(p->p.setScore(7));}
        assertTrue(((List<ScreenService.ParticipantView>)model.get("participants")).stream().allMatch(p->p.score()==0));assertSpectator(s,state);
    }
    @ParameterizedTest @EnumSource(MatchEndType.class)
    void tc085NormalAndAbortedKeepBothSpectatorStatesAndSameResult(MatchEndType end) throws Exception {
        start(end==MatchEndType.NORMAL?1:3,false);var c=spectator(UserState.ROOM_WAITING);var d=spectator(UserState.READY);
        if(end==MatchEndType.NORMAL){submit(a,"ROCK");submit(b,"SCISSORS");synchronized(lock){clock.now=matches.findById(matchId).orElseThrow().getTransitionAt();}transition();}
        else roomService.leaveRoom(a,roomId.toString());
        assertEquals(end,results.findById(matchId).orElseThrow().getEndType());assertSpectator(c,UserState.ROOM_WAITING);assertSpectator(d,UserState.READY);
        for(var s:List.of(c,d)){
            mvc.perform(get("/play").session(s).param("matchId",matchId.toString())).andExpect(redirectedUrl("/match-result?matchId="+matchId));
            mvc.perform(get("/match-result").session(s).param("matchId",matchId.toString())).andExpect(status().isOk()).andExpect(model().attribute("matchId",matchId.toString()));
        }
        assertEquals(UserState.ROOM_WAITING,user(b).getState());assertEquals(2,results.findById(matchId).orElseThrow().getParticipantNames().size());
    }
    @ParameterizedTest @EnumSource(value=UserState.class,names={"ROOM_WAITING","READY"})
    void observingDoesNotAffectReadyCancelOrLeave(UserState state) throws Exception {
        start(3,false);var s=spectator(state);mvc.perform(get("/play").session(s).param("matchId",matchId.toString()));mvc.perform(get("/room").session(s));
        if(state==UserState.ROOM_WAITING){ready(s,"HC");assertEquals(UserState.READY,user(s).getState());}
        else{mvc.perform(post("/room/ready/cancel").session(s).param("roomId",roomId.toString())).andExpect(redirectedUrl("/room"));assertEquals(UserState.ROOM_WAITING,user(s).getState());}
        roomService.leaveRoom(s,roomId.toString());assertEquals(UserState.ROOM_NONE,user(s).getState());
        mvc.perform(get("/play").session(s).param("matchId",matchId.toString())).andExpect(redirectedUrl("/rooms"));
        assertEquals(MatchState.SELECTING_HAND,matches.findById(matchId).orElseThrow().getState());
    }
    @Test void spectatorsDoNotPreventAbortAndNextRoundUsesSameTarget() throws Exception {
        start(3,false);var c=spectator(UserState.ROOM_WAITING);var d=spectator(UserState.READY);submit(a,"ROCK");submit(b,"ROCK");
        var screen=screens.gamePage(c,matchId.toString());assertEquals("round-result",screen.template());assertEquals(1,((List<?>)screen.model().get("roundHistory")).size());
        synchronized(lock){clock.now=matches.findById(matchId).orElseThrow().getTransitionAt();}transition();
        assertEquals(2,statuses.status(c,matchId.toString()).displayRoundNumber());assertEquals("play",screens.gamePage(d,matchId.toString()).template());
        roomService.leaveRoom(a,roomId.toString());assertEquals(MatchEndType.ABORTED,results.findById(matchId).orElseThrow().getEndType());assertSpectator(c,UserState.ROOM_WAITING);assertSpectator(d,UserState.READY);
    }
    @ParameterizedTest @EnumSource(value=MatchState.class,names={"SELECTING_HAND","ROUND_RESULT"})
    void newParticipantMatchTakesPriorityOverOldSpectatingId(MatchState state) throws Exception {
        start(3,false);var d=spectator(UserState.READY);var old=matchId;service.abortMatch(old);ready(a,"HA");
        mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));matchId=rooms.findById(roomId).orElseThrow().getCurrentMatchId();
        assertEquals(UserState.PLAYING,user(d).getState());if(state==MatchState.ROUND_RESULT){submit(a,"ROCK");submit(d,"ROCK");}
        String destination=(state==MatchState.SELECTING_HAND?"/play":"/round-result")+"?matchId="+matchId;
        for(String page:List.of("play","round-result"))mvc.perform(get("/"+page).session(d).param("matchId",old.toString())).andExpect(redirectedUrl(destination));
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();}
    HttpResponse<String> http(HttpClient client,String path,String body)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(body==null)r.GET();else r.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));return client.send(r.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void redirect(HttpResponse<String> r,String path){assertEquals(302,r.statusCode(),r.body());assertTrue(r.headers().firstValue("location").orElseThrow().endsWith(path));}
    @ParameterizedTest @CsvSource({"ROOM_WAITING,NORMAL","READY,NORMAL","ROOM_WAITING,ORIGINAL","READY,ORIGINAL"})
    void realHttpSpectatorHtmlJsonPrivacyAndPublishedRound(UserState state,String type)throws Exception {
        start(3,false);var c=browser();redirect(http(c,"/login","username=C"),"/rooms");redirect(http(c,"/rooms/enter","roomName=R"),"/room");
        if(state==UserState.READY){redirect(http(c,"/original-hand/save","returnPage=ROOM&roomId="+roomId+"&name=HC&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE"),"/room");redirect(http(c,"/room/ready","roomId="+roomId),"/room");}
        var room=http(c,"/room",null);assertEquals(200,room.statusCode());assertTrue(room.body().contains("現在対戦中です"));assertTrue(room.body().contains("/play?matchId="+matchId));
        String body="matchId="+matchId+"&roundNumber=1&type="+type+"&normalHand=ROCK&originalHandId="+matches.findById(matchId).orElseThrow().getOriginalHands().getFirst().getHandId();
        if(type.equals("NORMAL"))submit(a,"ROCK");else mvc.perform(post("/play").session(a).param("matchId",matchId.toString()).param("roundNumber","1").param("type","ORIGINAL").param("originalHandId",matches.findById(matchId).orElseThrow().getOriginalHands().getFirst().getHandId().toString()));
        String html=http(c,"/play?matchId="+matchId,null).body();String json=http(c,"/api/status?matchId="+matchId,null).body();
        assertTrue(html.contains("観戦中です"));assertTrue(html.contains("ルームへ戻る"));assertTrue(html.contains("Alice"));assertTrue(html.contains("Bob"));
        for(String secret:List.of("action=\"/play\"","ルームを退出","手を選択","self-hand-status","グー","チョキ","パー","HA","HB","同じオリジナル手"))assertFalse(html.contains(secret),secret);
        for(String secret:List.of("vsRock","vsScissors","vsPaper","vsOriginal","selections","ROCK","\"HA\"","\"HB\"","グー")){assertFalse(html.contains(secret),secret);assertFalse(json.contains(secret),secret);}
        var j=tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);assertEquals(state.name(),j.get("userState").asText());assertTrue(j.get("selfHandConfirmed").isNull());assertTrue(j.get("selfSelectedHand").isNull());
        assertEquals(409,http(c,"/play",body).statusCode());submit(b,"ROCK");redirect(http(c,"/play?matchId="+matchId,null),"/round-result?matchId="+matchId);
        html=http(c,"/round-result?matchId="+matchId,null).body();assertTrue(html.contains(type.equals("NORMAL")?"グー":"HA"));assertTrue(html.contains("ルームへ戻る"));assertFalse(html.contains("ルームを退出"));assertTrue(html.contains("ラウンド履歴"));
        for(String secret:List.of("vsRock","vsScissors","vsPaper","vsOriginal"))assertFalse(html.contains(secret));
        assertEquals(200,http(c,"/room",null).statusCode());assertEquals(state.name(),tools.jackson.databind.json.JsonMapper.builder().build().readTree(http(c,"/api/status",null).body()).get("userState").asText());
    }
    @Test void targetIdsAndSessionStayIndependentAcrossOldResultAndNewSpectating() throws Exception {
        normal();var old=matchId;var c=spectator(UserState.ROOM_WAITING);
        ready(a,"HA");ready(b,"HB");mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));
        matchId=rooms.findById(roomId).orElseThrow().getCurrentMatchId();var current=matchId;
        for(int phase=0;phase<3;phase++){
            assertEquals(current,statuses.status(c,current.toString()).displayMatchId());
            mvc.perform(get("/match-result").session(c).param("matchId",old.toString())).andExpect(status().isOk()).andExpect(model().attribute("matchId",old.toString()));
            if(phase==0){submit(a,"ROCK");submit(b,"SCISSORS");}
            if(phase==1){synchronized(lock){clock.now=matches.findById(current).orElseThrow().getTransitionAt();}transition();}
        }
        assertEquals(Set.of(SessionUserAccess.USER_ID),new HashSet<>(Collections.list(c.getAttributeNames())));
        assertSpectator(c,UserState.ROOM_WAITING);
    }

}
