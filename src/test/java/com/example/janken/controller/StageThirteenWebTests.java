package com.example.janken.controller;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
import com.example.janken.service.*;
import com.example.janken.store.*;
import com.example.janken.scheduler.MatchTransitionScheduler;
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
class StageThirteenWebTests {
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
    MatchTransitionScheduler scheduler;
    MockMvc mvc; MockHttpSession a,b,c; UUID roomId,matchId;
    @BeforeEach void setup(){
        synchronized(lock){users.findAll().forEach(u->users.deleteById(u.getId()));rooms.findAll().forEach(r->rooms.deleteById(r.getId()));
            matches.findAll().forEach(m->matches.deleteById(m.getId()));results.findAll().forEach(r->results.deleteById(r.getMatchId()));
            clock.now=Instant.parse("2026-10-07T00:00:00Z");}
        scheduler=new MatchTransitionScheduler(lock,matches,service,clock);
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


    @ParameterizedTest @ValueSource(strings={"draw","winner","final"})
    void countdownZeroWaitsForSchedulerThenStatusReflectsSameMatch(String mode) throws Exception {
        boolean normal=mode.equals("final"); start(normal?1:3,false); submit(a,"ROCK"); submit(b,mode.equals("draw")?"ROCK":"SCISSORS");
        Instant deadline; synchronized(lock){deadline=matches.findById(matchId).orElseThrow().getTransitionAt();clock.now=deadline;}
        // カウント0に相当する期限到達だけでは、API・GETともに結果表示を維持する。
        mvc.perform(get("/api/status").session(a).param("matchId",matchId.toString()))
            .andExpect(jsonPath("$.displayMatchState").value("ROUND_RESULT"))
            .andExpect(jsonPath("$.displayMatchId").value(matchId.toString()));
        mvc.perform(get("/round-result").session(a).param("matchId",matchId.toString())).andExpect(status().isOk()).andExpect(view().name("round-result"));
        scheduler.runTransitions();
        mvc.perform(get("/api/status").session(a).param("matchId",matchId.toString()))
            .andExpect(jsonPath("$.displayMatchState").value(normal?"MATCH_RESULT":"SELECTING_HAND"))
            .andExpect(jsonPath("$.displayMatchId").value(matchId.toString()));
        mvc.perform(get("/round-result").session(a).param("matchId",matchId.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl((normal?"/match-result":"/play")+"?matchId="+matchId));
        if(normal){
            assertEquals(MatchEndType.NORMAL,results.findById(matchId).orElseThrow().getEndType());
            mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andExpect(status().isOk()).andExpect(view().name("match-result"));
        } else {
            mvc.perform(post("/play").session(a).param("matchId",matchId.toString()).param("roundNumber","1")
                .param("type","NORMAL").param("normalHand","ROCK")).andExpect(status().isConflict());
            assertTrue(matches.findById(matchId).orElseThrow().getCurrentRound().getSelections().isEmpty());
        }
    }
}
