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
class StageTwelveWebTests {
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

    @ParameterizedTest @EnumSource(value=UserState.class,names={"ROOM_WAITING","READY"})
    void sameRoomNonParticipantMayViewAndReturnWithoutStateChange(UserState state) throws Exception {
        normal();var viewer=login("Visitor","R");if(state==UserState.READY)ready(viewer,"HV");
        mvc.perform(get("/match-result").session(viewer).param("matchId",matchId.toString())).andExpect(status().isOk()).andExpect(view().name("match-result"))
            .andExpect(model().attributeExists("roomId","matchId","endType","winners","finalScores","roundHistory","originalHandAffinities","userState","matchResultReturnForm"))
            .andExpect(model().attribute("canEditOriginalHand",state == UserState.ROOM_WAITING)).andExpect(model().attribute("userState",state));
        if (state == UserState.READY) {
            mvc.perform(get("/match-result").session(viewer).param("matchId",matchId.toString()))
                .andExpect(model().attributeDoesNotExist("originalHandForm","originalHandDeleteForm"));
        }
        mvc.perform(post("/match-result/return").session(viewer).param("roomId",roomId.toString()).param("matchId",matchId.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/room"));assertEquals(state,user(viewer).getState());
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","none","otherRoom","invalid","missing","ongoing","empty"})
    void resultGetDoesNotDiscloseInvalidOrUnauthorizedTarget(String mode) throws Exception {
        normal();MockHttpSession viewer=a;String id=matchId.toString();String target="/room";
        switch(mode){
            case "anonymous" -> {viewer=null;target="/";}
            case "none" -> {viewer=login("None",null);target="/rooms";}
            case "otherRoom" -> viewer=login("Other","Other");
            case "invalid" -> id="not-a-uuid";
            case "missing" -> id=UUID.randomUUID().toString();
            case "empty" -> id="";
            default -> {var ongoing=new GameMatch(UUID.randomUUID(),roomId,"R",3,false);synchronized(lock){matches.save(ongoing);}id=ongoing.getId().toString();}
        }
        var request=get("/match-result").param("matchId",id);if(viewer!=null)request.session(viewer);
        mvc.perform(request).andExpect(status().isFound()).andExpect(redirectedUrl(target));
    }
    @Test void sameNameRecreatedRoomDoesNotGrantOldResultAccess() throws Exception {
        normal();UUID old=matchId;String rid=roomId.toString();
        mvc.perform(post("/room/leave").session(a).param("roomId",rid));mvc.perform(post("/room/leave").session(b).param("roomId",rid));
        mvc.perform(post("/rooms/enter").session(a).param("roomName","R"));assertNotEquals(roomId,user(a).getCurrentRoomId());
        mvc.perform(get("/match-result").session(a).param("matchId",old.toString())).andExpect(redirectedUrl("/room"));
        mvc.perform(get("/api/status").session(a).param("matchId",old.toString())).andExpect(content().string(org.hamcrest.Matchers.containsString("\"displayMatchId\":null")));
    }
    @ParameterizedTest @ValueSource(strings={"/play","/round-result"})
    void endedMatchGetRoutesToSameResultWithoutSpectating(String path) throws Exception {
        normal();mvc.perform(get(path).session(a).param("matchId",matchId.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/match-result?matchId="+matchId));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void resultIdOmittedRedirectsOnceOrRoomWhenNoResult(boolean exists) throws Exception {
        if(exists)normal();else {a=login("Alice","R");}
        mvc.perform(get("/match-result").session(a)).andExpect(status().isFound()).andExpect(redirectedUrl(exists?"/match-result?matchId="+matchId:"/room"));
    }
    @ParameterizedTest @ValueSource(strings={"SELECTING_HAND","ROUND_RESULT"})
    void playingPrioritizesCurrentMatchForGetAndOldReturn(String state) throws Exception {
        normal();UUID old=matchId;ready(a,"HA");ready(b,"HB");mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));
        synchronized(lock){matchId=rooms.findById(roomId).orElseThrow().getCurrentMatchId();}
        if(state.equals("ROUND_RESULT")){submit(a,"ROCK");submit(b,"SCISSORS");}
        String canonical=(state.equals("SELECTING_HAND")?"/play":"/round-result")+"?matchId="+matchId;
        mvc.perform(get("/match-result").session(a).param("matchId",old.toString())).andExpect(status().isFound()).andExpect(redirectedUrl(canonical));
        mvc.perform(post("/match-result/return").session(a).param("roomId",UUID.randomUUID().toString()).param("matchId",old.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl(canonical));assertEquals(UserState.PLAYING,user(a).getState());
    }
    @ParameterizedTest @ValueSource(strings={"wrongRoom","badRoom","missingRoom","missingResult","invalidResult","ongoing","foreignResult"})
    void invalidReturn409DoesNotMutateUserOrResult(String mode) throws Exception {
        normal();String rid=roomId.toString();String mid=matchId.toString();var before=results.findById(matchId).orElseThrow();
        switch(mode){
            case "wrongRoom" -> rid=UUID.randomUUID().toString();case "badRoom" -> rid="bad";case "missingRoom" -> rid="";
            case "missingResult" -> mid=UUID.randomUUID().toString();case "invalidResult" -> mid="bad";
            case "ongoing" -> {var m=new GameMatch(UUID.randomUUID(),roomId,"R",3,false);synchronized(lock){matches.save(m);}mid=m.getId().toString();}
            default -> {var snapshot=new MatchResultSnapshot(UUID.randomUUID(),UUID.randomUUID(),"秘密",3,false,Map.of(),MatchEndType.ABORTED,List.of(),Map.of(),List.of(),List.of(),clock.now);synchronized(lock){results.save(snapshot);}mid=snapshot.getMatchId().toString();}
        }
        mvc.perform(post("/match-result/return").session(a).param("roomId",rid).param("matchId",mid))
            .andExpect(status().isConflict()).andExpect(view().name("room")).andExpect(model().attribute("errorCode","INVALID_STATE"));
        assertEquals(UserState.ROOM_WAITING,user(a).getState());assertSame(before,results.findById(matchId).orElseThrow());
    }
    @ParameterizedTest @ValueSource(strings={"leave","logout"})
    void playingManualLeaveOrLogoutIsAcceptedAndAborts(String operation) throws Exception {
        start(3,false);var before=user(a);var hand=before.getOriginalHand();
        mvc.perform(post(operation.equals("leave")?"/room/leave":"/logout").session(a).param("roomId",roomId.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl(operation.equals("leave")?"/rooms":"/"));
        assertEquals(UserState.ROOM_NONE,before.getState());assertSame(hand,before.getOriginalHand());
        assertEquals(MatchEndType.ABORTED,results.findById(matchId).orElseThrow().getEndType());assertEquals(UserState.ROOM_WAITING,user(b).getState());
        if(operation.equals("logout")){assertTrue(a.isInvalid());assertTrue(users.findById(before.getId()).isEmpty());}
        else {assertSame(before,user(a));mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andExpect(redirectedUrl("/rooms"));}
    }
    @Test void resultModelAllParticipantsHistoryAffinitiesAndMultipleWinners() throws Exception {
        start(1,true);submit(a,"ROCK");submit(b,"ROCK");submit(c,"SCISSORS");
        mvc.perform(post("/room/leave").session(c).param("roomId",roomId.toString()));
        synchronized(lock){clock.now=matches.findById(matchId).orElseThrow().getTransitionAt();}service.finishNormal(matchId);
        var model=mvc.perform(get("/match-result").session(a).param("matchId",matchId.toString())).andReturn().getModelAndView().getModel();
        assertEquals(MatchEndType.NORMAL,model.get("endType"));assertEquals(2,((List<?>)model.get("winners")).size());
        var scores=((List<?>)model.get("finalScores")).stream().map(ScreenService.ScoreView.class::cast).toList();assertEquals(List.of("Alice","Bob","Carol"),scores.stream().map(ScreenService.ScoreView::username).toList());
        assertEquals(List.of(1,1,0),scores.stream().map(ScreenService.ScoreView::score).toList());
        var history=((List<?>)model.get("roundHistory")).stream().map(ScreenService.HistoryView.class::cast).toList();assertEquals(1,history.size());assertEquals(3,history.getFirst().results().size());
        assertEquals(3,((List<?>)model.get("originalHandAffinities")).size());
        var saved=results.findById(matchId).orElseThrow();user(a).getOriginalHand().setVsRock(HandRelation.LOSE);
        assertEquals(HandRelation.WIN,saved.getOriginalHandAffinities().getFirst().getVsRock());
    }
    @Test void m1RemainsTargetAfterM2EndsAndReturnDoesNotAffectOtherResultTabs() throws Exception {
        normal();UUID m1=matchId;var viewer=login("Viewer","R");ready(a,"HA");ready(b,"HB");mvc.perform(post("/room/start").session(a).param("roomId",roomId.toString()));
        synchronized(lock){matchId=rooms.findById(roomId).orElseThrow().getCurrentMatchId();}
        mvc.perform(get("/match-result").session(viewer).param("matchId",m1.toString())).andExpect(model().attribute("matchId",m1.toString()));service.abortMatch(matchId);
        mvc.perform(get("/match-result").session(viewer).param("matchId",m1.toString())).andExpect(model().attribute("matchId",m1.toString()));
        mvc.perform(get("/match-result").session(viewer).param("matchId",matchId.toString())).andExpect(model().attribute("matchId",matchId.toString()));
        String json=mvc.perform(get("/api/status").session(viewer).param("matchId",m1.toString())).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("\"displayMatchId\":\""+m1+"\""));assertTrue(json.contains("\"displayMatchState\":\"MATCH_RESULT\""));
        mvc.perform(post("/match-result/return").session(viewer).param("roomId",roomId.toString()).param("matchId",matchId.toString())).andExpect(redirectedUrl("/room"));
        mvc.perform(get("/match-result").session(viewer).param("matchId",m1.toString())).andExpect(model().attribute("matchId",m1.toString()));
        assertEquals(1,Collections.list(viewer.getAttributeNames()).size());
    }

    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();}
    HttpResponse<String> http(HttpClient client,String url,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+url)).timeout(Duration.ofSeconds(10));
        if(body==null)builder.GET();else builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void redirect(HttpResponse<String> response,String target){assertEquals(302,response.statusCode(),response.body());assertTrue(response.headers().firstValue("location").orElseThrow().endsWith(target));}
    String hidden(String html,String name){var p=java.util.regex.Pattern.compile("name=\""+name+"\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(p.find(),html);return p.group(1);}
    void httpReady(HttpClient client,String rid,String hand) throws Exception {
        redirect(http(client,"/original-hand/save","returnPage=ROOM&roomId="+rid+"&name="+hand+"&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE"),"/room");
        redirect(http(client,"/room/ready","roomId="+rid),"/room");
    }
    @ParameterizedTest @ValueSource(strings={"normal","aborted","nextRound"})
    void realHttpFlowHtmlAndStatus(String mode) throws Exception {
        var ca=browser();var cb=browser();
        for(var pair:List.of(Map.entry(ca,"Alice"),Map.entry(cb,"Bob"))){redirect(http(pair.getKey(),"/login","username="+pair.getValue()),"/rooms");redirect(http(pair.getKey(),"/rooms/enter","roomName=R"),"/room");}
        String rid=hidden(http(ca,"/room",null).body(),"roomId");
        if(mode.equals("normal"))redirect(http(ca,"/room/rules","roomId="+rid+"&targetWins=1"),"/room");
        httpReady(ca,rid,"HA");httpReady(cb,rid,"HB");var response=http(ca,"/room/start","roomId="+rid);assertEquals(302,response.statusCode());
        String mid=response.headers().firstValue("location").orElseThrow().split("matchId=")[1];UUID id=UUID.fromString(mid);
        String play=http(ca,"/play?matchId="+mid,null).body();assertTrue(play.contains("/room/leave"));assertTrue(play.contains("/logout"));
        redirect(http(ca,"/play","matchId="+mid+"&roundNumber=1&type=NORMAL&normalHand=ROCK"),"/play?matchId="+mid);
        redirect(http(cb,"/play","matchId="+mid+"&roundNumber=1&type=NORMAL&normalHand=SCISSORS"),"/play?matchId="+mid);
        assertTrue(http(ca,"/round-result?matchId="+mid,null).body().contains("/room/leave"));
        if(mode.equals("aborted")){redirect(http(cb,"/room/leave","roomId="+rid),"/rooms");}
        else {synchronized(lock){clock.now=matches.findById(id).orElseThrow().getTransitionAt();}service.advanceMatch(id);}
        String json=http(ca,"/api/status?matchId="+mid,null).body();
        if(mode.equals("nextRound")){
            assertTrue(json.contains("\"displayMatchState\":\"SELECTING_HAND\""));redirect(http(ca,"/round-result?matchId="+mid,null),"/play?matchId="+mid);
            assertTrue(http(ca,"/play?matchId="+mid,null).body().contains("data-round-number=\"2\""));return;
        }
        assertTrue(json.contains("\"currentMatchId\":null"));assertTrue(json.contains("\"displayMatchId\":\""+mid+"\""));
        assertTrue(json.contains("\"displayMatchState\":\"MATCH_RESULT\""));
        var result=http(ca,"/match-result?matchId="+mid,null);assertEquals(200,result.statusCode(),result.body());String html=result.body();
        for(String text:List.of("対戦終了","最終結果","Alice","Bob","ラウンド履歴","グー","チョキ","HA","HB","勝ち","負け","引き分け","他のオリジナル手","ルームへ戻る"))assertTrue(html.contains(text),text);
        assertTrue(html.contains("id=\"original-hand-affinities\" class=\"table-scroll\" hidden"));assertTrue(html.contains("/js/status-polling.js"));assertTrue(html.contains("/js/match-result.js"));
        // 第14段階で②向け編集を統合したため、終了済み結果の閲覧に加えて導線を確認する。
        assertTrue(html.contains("/original-hand/save"));assertTrue(html.contains("/original-hand/delete"));assertTrue(html.contains("自分のオリジナル手を編集"));
        if(mode.equals("aborted")){assertTrue(html.contains("参加人数が不足したため対戦を終了しました"));assertTrue(html.contains("勝者はいません"));}else assertTrue(html.contains("<h2>勝者</h2>"));
        // 制御用属性とhiddenを除いた本文に内部IDを出さない。
        String bodyText=html.replaceAll("<[^>]+>","");assertFalse(bodyText.contains(mid));assertFalse(bodyText.contains(rid));
        synchronized(lock){for(var p:matches.findById(id).orElseThrow().getParticipants().values())assertFalse(bodyText.contains(p.getUserId().toString()));}
        redirect(http(ca,"/match-result",null),"/match-result?matchId="+mid);
        redirect(http(ca,"/match-result/return","roomId="+rid+"&matchId="+mid),"/room");
    }
}
