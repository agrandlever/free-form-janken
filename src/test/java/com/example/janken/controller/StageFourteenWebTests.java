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
class StageFourteenWebTests {
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


    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder operation(boolean delete, Map<String,String> changes) {
        var params=new LinkedHashMap<String,String>();
        params.put("returnPage","MATCH_RESULT");params.put("roomId",roomId.toString());params.put("resultMatchId",matchId.toString());
        if(!delete){params.put("name","更新手");params.put("vsRock","LOSE");params.put("vsScissors","WIN");params.put("vsPaper","DRAW");params.put("vsOriginal","DRAW");}
        params.putAll(changes);var request=post(delete?"/original-hand/delete":"/original-hand/save").session(a);
        params.forEach((k,v)->{if(v!=null)request.param(k,v);});return request;
    }
    List<Object> handValues(){var h=user(a).getOriginalHand();return h==null?List.of():List.of(h.getId(),h.getName(),h.getVsRock(),h.getVsScissors(),h.getVsPaper(),h.getVsOriginal());}
    Object past(){var s=results.findById(matchId).orElseThrow();return List.of(s.getParticipantNames(),s.getWinnerIds(),s.getFinalScores(),s.getRoundHistory(),s.getOriginalHandAffinities());}
    void normalModel(Map<String,Object> m){for(String key:List.of("winners","finalScores","roundHistory","originalHandAffinities","matchResultReturnForm","username","userState"))assertTrue(m.containsKey(key),key);assertEquals(matchId.toString(),m.get("matchId"));}
    @Test void tc073074076CurrentValuesAreSeparateAndReadyHasNoEditor() throws Exception {
        normal();var snapshot=results.findById(matchId).orElseThrow();
        mvc.perform(operation(false,Map.of())).andExpect(redirectedUrl("/match-result?matchId="+matchId));
        var m=mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andReturn().getModelAndView().getModel();normalModel(m);
        var f=(com.example.janken.form.OriginalHandForm)m.get("originalHandForm");
        assertEquals("更新手",f.getName());assertEquals(HandRelation.LOSE,f.getVsRock());assertEquals("MATCH_RESULT",f.getReturnPage());assertEquals(roomId.toString(),f.getRoomId());assertEquals(matchId.toString(),f.getResultMatchId());
        var d=(com.example.janken.form.OriginalHandDeleteForm)m.get("originalHandDeleteForm");assertEquals(f.getReturnPage(),d.getReturnPage());assertEquals(f.getRoomId(),d.getRoomId());assertEquals(f.getResultMatchId(),d.getResultMatchId());
        assertEquals(HandRelation.WIN,snapshot.getOriginalHandAffinities().getFirst().getVsRock());assertEquals(true,m.get("canEditOriginalHand"));
        mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString())).andExpect(status().isFound());
        mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andExpect(model().attribute("canEditOriginalHand",false));
    }
    static java.util.stream.Stream<Arguments> badInputs(){
        var list=new ArrayList<Arguments>();
        for(String name:List.of(""," ","😀".repeat(21),"グー"," グー ","チョキ","パー"))list.add(Arguments.of("name",name));list.add(Arguments.of("name",null));
        for(String f:List.of("vsRock","vsScissors","vsPaper","vsOriginal"))list.add(Arguments.of(f,"BAD"));
        list.add(Arguments.of("vsRock","WIN"));list.add(Arguments.of("returnPage","BAD"));
        for(String f:List.of("roomId","resultMatchId"))for(String v:Arrays.asList(null,"","bad","1-1-1-1-1"))list.add(Arguments.of(f,v));return list.stream();
    }
    @ParameterizedTest @MethodSource("badInputs")
    void tc113SaveInputErrorsPreserveAllValuesAndPast(String field,String value) throws Exception {
        normal();var old=handValues();var past=past();var changes=new HashMap<String,String>();changes.put(field,value);
        var m=mvc.perform(operation(false,changes)).andExpect(status().isBadRequest()).andExpect(model().attribute("errorCode","VALIDATION_ERROR"))
            .andExpect(model().attributeExists("errorMessages","fieldErrors","form")).andReturn().getModelAndView().getModel();
        assertEquals(old,handValues());assertEquals(past,past());assertTrue(((Map<?,?>)m.get("fieldErrors")).containsKey(field.equals("vsRock")&&"WIN".equals(value)?"vsOriginal":field));
        if(!field.equals("resultMatchId")&&!field.equals("returnPage")){normalModel(m);assertEquals(true,m.get("originalHandFormOpen"));}
        if(field.equals("name"))assertEquals(value,((com.example.janken.form.OriginalHandForm)m.get("originalHandForm")).getName());
    }
    static java.util.stream.Stream<Arguments> badTargets(){return java.util.stream.Stream.of(false,true).flatMap(d->java.util.stream.Stream.of("READY","PLAYING","wrongRoom","missing","ongoing","otherRoom","sameName").map(mode->Arguments.of(d,mode)));}
    @ParameterizedTest @MethodSource("badTargets")
    void stateErrorsNeverMutateAndPlayingUsesCanonicalScreen(boolean delete,String mode) throws Exception {
        normal();var old=handValues();var past=past();var changes=new HashMap<String,String>();
        switch(mode){
            case "READY" -> mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString()));
            case "PLAYING" -> {mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString()));mvc.perform(post("/room/ready").session(b).param("roomId",roomId.toString()));mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));}
            case "wrongRoom" -> changes.put("roomId",UUID.randomUUID().toString());case "missing" -> changes.put("resultMatchId",UUID.randomUUID().toString());
            case "ongoing" -> {var m=new GameMatch(UUID.randomUUID(),roomId,"R",3,false);synchronized(lock){matches.save(m);}changes.put("resultMatchId",m.getId().toString());}
            default -> {var s=new MatchResultSnapshot(UUID.randomUUID(),UUID.randomUUID(),mode.equals("sameName")?"R":"別Room",3,false,Map.of(),MatchEndType.ABORTED,List.of(),Map.of(),List.of(),List.of(),clock.now);synchronized(lock){results.save(s);}changes.put("resultMatchId",s.getMatchId().toString());}
        }
        var m=mvc.perform(operation(delete,changes)).andExpect(status().isConflict()).andExpect(model().attribute("errorCode","INVALID_STATE"))
            .andExpect(model().attributeExists("errorMessages","fieldErrors","form")).andReturn().getModelAndView();assertEquals(old,handValues());assertEquals(past,past());
        if(mode.equals("READY")){assertEquals("match-result",m.getViewName());normalModel(m.getModel());assertEquals(false,m.getModel().get("canEditOriginalHand"));assertEquals(false,m.getModel().get("originalHandFormOpen"));}
        if(mode.equals("PLAYING")){assertEquals("play",m.getViewName());var next=matches.findById(rooms.findById(roomId).orElseThrow().getCurrentMatchId()).orElseThrow();assertEquals("HA",next.getOriginalHands().getFirst().getName());}
    }
    static java.util.stream.Stream<Arguments> deleteIds(){return java.util.stream.Stream.of("roomId","resultMatchId").flatMap(f->java.util.stream.Stream.of(null,"","bad","1-1-1-1-1").map(v->Arguments.of(f,v)));}
    @ParameterizedTest @MethodSource("deleteIds")
    void deleteBadRequiredIds400(String field,String value) throws Exception {
        normal();var old=handValues();var past=past();var changes=new HashMap<String,String>();changes.put(field,value);
        mvc.perform(operation(true,changes)).andExpect(status().isBadRequest()).andExpect(model().attribute("errorCode","VALIDATION_ERROR"));assertEquals(old,handValues());assertEquals(past,past());
    }
    @Test void tc112M1SurvivesM2SaveErrorDeleteRecreateAndNextSnapshot() throws Exception {
        normal();var m1=matchId;var past=past();var originalId=user(a).getOriginalHand().getId();
        mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString()));mvc.perform(post("/room/ready").session(b).param("roomId",roomId.toString()));mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));
        var m2=rooms.findById(roomId).orElseThrow().getCurrentMatchId();service.abortMatch(m2);assertEquals(m2,rooms.findById(roomId).orElseThrow().getLastCompletedMatchId());
        mvc.perform(operation(false,Map.of())).andExpect(redirectedUrl("/match-result?matchId="+m1));assertEquals(originalId,user(a).getOriginalHand().getId());assertEquals(past,past());
        mvc.perform(operation(false,Map.of("name"," "))).andExpect(status().isBadRequest()).andExpect(view().name("match-result")).andExpect(model().attribute("matchId",m1.toString())).andExpect(model().attribute("originalHandFormOpen",true));
        mvc.perform(operation(true,Map.of())).andExpect(redirectedUrl("/match-result?matchId="+m1));assertNull(user(a).getOriginalHand());assertEquals(past,past());
        var m=mvc.perform(get("/match-result").session(a).param("matchId",m1.toString())).andReturn().getModelAndView().getModel();assertEquals(false,m.get("hasOriginalHand"));assertNull(((com.example.janken.form.OriginalHandForm)m.get("originalHandForm")).getName());
        mvc.perform(operation(true,Map.of())).andExpect(status().isConflict());mvc.perform(operation(false,Map.of())).andExpect(redirectedUrl("/match-result?matchId="+m1));assertNotEquals(originalId,user(a).getOriginalHand().getId());assertEquals(past,past());
        mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString())).andExpect(status().isFound());mvc.perform(post("/room/ready").session(b).param("roomId",roomId.toString()));mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));
        var next=matches.findById(rooms.findById(roomId).orElseThrow().getCurrentMatchId()).orElseThrow();assertEquals("更新手",next.getOriginalHands().getFirst().getName());assertEquals(HandRelation.LOSE,next.getOriginalHands().getFirst().getVsRock());assertEquals(past,past());
        mvc.perform(operation(false,Map.of())).andExpect(status().isConflict());assertEquals("更新手",next.getOriginalHands().getFirst().getName());
    }
    @Test void tc075ViewingThenReadyHasNoWarning() throws Exception {
        normal();var old=handValues();mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andExpect(status().isOk());assertEquals(old,handValues());assertEquals(UserState.ROOM_WAITING,user(a).getState());
        mvc.perform(post("/match-result/return").session(a).param("roomId",roomId.toString()).param("matchId",matchId.toString())).andExpect(redirectedUrl("/room"));mvc.perform(post("/room/ready").session(a).param("roomId",roomId.toString())).andExpect(status().isFound());assertEquals(UserState.READY,user(a).getState());
    }
}
