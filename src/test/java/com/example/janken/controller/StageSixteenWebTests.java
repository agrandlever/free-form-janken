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
class StageSixteenWebTests {
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


    @Autowired RoomService roomService;
    @Autowired StatusService statuses;
    void monitor() { new com.example.janken.scheduler.DisconnectMonitor(lock, users, roomService, clock).runChecks(); }
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    HttpResponse<String> http(HttpClient client, String path, String data) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(data == null) builder.GET(); else builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(data));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    void redirect(HttpResponse<String> response, String path) {
        assertEquals(302, response.statusCode(), response.body()); assertTrue(response.headers().firstValue("location").orElseThrow().endsWith(path));
    }
    void prepare(UserState state) throws Exception {
        if(state == UserState.PLAYING) { start(3,true); }
        else { a=login("Alice",state==UserState.ROOM_NONE?null:"R"); if(state==UserState.READY)ready(a,"HA"); }
    }
    @ParameterizedTest @EnumSource(value=UserState.class,names={"ROOM_WAITING","READY","PLAYING"})
    void realHttpPollingTimeoutKeepsCookieIdentityAndHand(UserState state) throws Exception {
        var c=client(); redirect(http(c,"/login","username=HttpA"),"/rooms"); redirect(http(c,"/rooms/enter","roomName=HttpR"),"/room");
        GameUser u; UUID rid;
        synchronized(lock){u=users.findAll().stream().filter(v->v.getUsername().equals("HttpA")).findFirst().orElseThrow();rid=u.getCurrentRoomId();}
        redirect(http(c,"/original-hand/save","returnPage=ROOM&roomId="+rid+"&name=HttpHA&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE"),"/room");
        if(state!=UserState.ROOM_WAITING)redirect(http(c,"/room/ready","roomId="+rid),"/room");
        var peers=new ArrayList<HttpClient>();
        if(state==UserState.PLAYING){var other=client();peers.add(other);http(other,"/login","username=HttpB");http(other,"/rooms/enter","roomName=HttpR");
            http(other,"/original-hand/save","returnPage=ROOM&roomId="+rid+"&name=HttpHB&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE");http(other,"/room/ready","roomId="+rid);http(c,"/room/start","roomId="+rid);}
        OriginalHand hand; synchronized(lock){hand=u.getOriginalHand();clock.now=clock.now.plusSeconds(25);}
        assertEquals(200,http(c,"/api/status",null).statusCode()); Instant heartbeat;
        synchronized(lock){heartbeat=clock.now;assertEquals(heartbeat,u.getLastSeenAt());clock.now=heartbeat.plusSeconds(30).minusNanos(1);}
        for(var peer:peers)assertEquals(200,http(peer,"/api/status",null).statusCode());
        monitor(); synchronized(lock){assertEquals(state,u.getState());}
        // 不正statusの400は通信期限を延長しない。正常statusはここでは停止している。
        assertEquals(400,http(c,"/api/status?matchId=bad",null).statusCode()); synchronized(lock){assertEquals(heartbeat,u.getLastSeenAt());clock.now=heartbeat.plusSeconds(30);}
        monitor();monitor(); synchronized(lock){assertEquals(UserState.ROOM_NONE,u.getState());assertNull(u.getCurrentRoomId());assertSame(hand,u.getOriginalHand());assertSame(u,users.findById(u.getId()).orElseThrow());assertEquals(heartbeat,u.getLastSeenAt());}
        var response=http(c,"/api/status",null);assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"userState\":\"ROOM_NONE\""),response.body());
        assertTrue(response.body().contains("\"room\":null"));redirect(http(c,"/room",null),"/rooms");
        var html=http(c,"/rooms",null);assertEquals(200,html.statusCode());assertTrue(html.body().contains("HttpA"));assertTrue(html.body().contains("HttpHA"));
        redirect(http(c,"/rooms/enter","roomName=AfterTimeout"),"/room");
    }
    @ParameterizedTest @EnumSource(UserState.class)
    void tc127RealHttpOldCookieRejectsEveryProtectedGetAndPost(UserState state) throws Exception {
        var c=client();http(c,"/login","username=OldCookie");GameUser u;
        if(state!=UserState.ROOM_NONE)http(c,"/rooms/enter","roomName=CookieR");
        synchronized(lock){u=users.findAll().stream().filter(v->v.getUsername().equals("OldCookie")).findFirst().orElseThrow();}
        UUID rid=u.getCurrentRoomId();UUID mid=UUID.randomUUID();
        if(state==UserState.READY || state==UserState.PLAYING){http(c,"/original-hand/save","returnPage=ROOM&roomId="+rid+"&name=CookieH&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE");http(c,"/room/ready","roomId="+rid);}
        var other=client();http(other,"/login","username=OtherCookie");
        if(state==UserState.PLAYING){http(other,"/rooms/enter","roomName=CookieR");http(other,"/original-hand/save","returnPage=ROOM&roomId="+rid+"&name=OtherH&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE");http(other,"/room/ready","roomId="+rid);http(c,"/room/start","roomId="+rid);synchronized(lock){mid=rooms.findById(rid).orElseThrow().getCurrentMatchId();}}
        // Set-Cookieを受けたCookie jarではなく、ログアウト前の文字列を再送して確認する。
        var manager=(CookieManager)c.cookieHandler().orElseThrow();String cookie=manager.getCookieStore().getCookies().stream().map(v->v.getName()+"="+v.getValue()).reduce((x,y)->x+"; "+y).orElseThrow();
        redirect(http(c,"/logout",""),"/");var raw=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String query="?matchId="+mid;
        for(String path:List.of("/rooms","/room","/play"+query,"/round-result"+query,"/match-result"+query)){
            var response=raw.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());redirect(response,"/");}
        var posts=new LinkedHashMap<String,String>();posts.put("/rooms/enter","roomName=Stale");
        for(String path:List.of("/room/leave","/room/ready","/room/ready/cancel","/room/start"))posts.put(path,"roomId="+rid);
        posts.put("/room/rules","roomId="+rid+"&targetWins=3&preventConsecutiveSameOriginalHand=false");
        posts.put("/original-hand/save","returnPage=ROOMS&name=HA&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=LOSE");
        posts.put("/original-hand/delete","returnPage=ROOMS");posts.put("/play","matchId="+mid+"&roundNumber=1&type=NORMAL&normalHand=ROCK");
        posts.put("/match-result/return","roomId="+rid+"&matchId="+mid);posts.put("/logout","");
        for(var entry:posts.entrySet()){
            var response=raw.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+entry.getKey())).header("Cookie",cookie).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(entry.getValue())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,response.statusCode(),entry.getKey()+response.body());assertTrue(response.body().contains("LOGIN_REQUIRED"));}
        var response=raw.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/status")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(401,response.statusCode());assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("json"));assertTrue(response.body().contains("LOGIN_REQUIRED"));
        assertFalse(response.body().contains("userState"));assertEquals(200,http(other,"/api/status",null).statusCode());synchronized(lock){assertTrue(users.findById(u.getId()).isEmpty());}
    }
    @ParameterizedTest @EnumSource(UserState.class)
    void tc128ControllerPriorityForEveryState(UserState state) throws Exception {
        prepare(state);var u=user(a);var oldState=u.getState();var oldRoom=u.getCurrentRoomId();var oldHand=u.getOriginalHand();int count=rooms.findAll().size();
        for(String name:List.of("", " ", "\t　", "a".repeat(21), "ValidNewRoom")){
            var result=mvc.perform(post("/rooms/enter").session(a).param("roomName",name)).andReturn();
            int expected=state==UserState.ROOM_NONE?(name.equals("ValidNewRoom")?302:400):409;assertEquals(expected,result.getResponse().getStatus());
            if(expected!=302){assertEquals(oldState,u.getState());assertEquals(oldRoom,u.getCurrentRoomId());assertSame(oldHand,u.getOriginalHand());assertEquals(count,rooms.findAll().size());}}
        mvc.perform(post("/rooms/enter").param("roomName","")).andExpect(status().isUnauthorized());
        mvc.perform(post("/rooms/enter").param("roomName","Valid")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings={"NORMAL","ORIGINAL"})
    void tc130SelfConfirmationAndMonitorCoexist(String type) throws Exception {
        start(3,true);String mid=matchId.toString();
        mvc.perform(get("/api/status").session(a).param("matchId",mid)).andExpect(jsonPath("$.selfHandConfirmed").value(false));
        var post=post("/play").session(a).param("matchId",mid).param("roundNumber","1").param("type",type);
        if(type.equals("NORMAL"))post.param("normalHand","ROCK");else post.param("originalHandId",user(b).getOriginalHand().getId().toString());
        mvc.perform(post).andExpect(status().isFound());
        for(int seconds=2;seconds<=64;seconds+=2){synchronized(lock){clock.now=Instant.parse("2026-10-07T00:00:00Z").plusSeconds(seconds);}
            mvc.perform(get("/api/status").session(a).param("matchId",mid)).andExpect(status().isOk()).andExpect(jsonPath("$.selfHandConfirmed").value(true));
            statuses.status(b,mid);statuses.status(c,mid);monitor();assertEquals(UserState.PLAYING,user(a).getState());}
        mvc.perform(post).andExpect(status().isConflict());
        var json=mvc.perform(get("/api/status").session(b).param("matchId",mid)).andReturn().getResponse().getContentAsString();assertTrue(json.contains("\"selfHandConfirmed\":false"));assertFalse(json.contains("vsRock"));
    }
    @ParameterizedTest @ValueSource(strings={"waiting", "noHand", "ready", "selecting", "confirmed", "result", "normal"})
    void tc129ManualLeaveMatchesTimeoutIdentityAndStatus(String mode) throws Exception {
        if(List.of("selecting","confirmed","result","normal").contains(mode)){
            start(mode.equals("normal")?1:3,true);
            if(mode.equals("confirmed"))submit(a,"ROCK");
            if(mode.equals("result")||mode.equals("normal")){submit(a,"ROCK");submit(b,"SCISSORS");submit(c,"SCISSORS");}
        }else{a=login("Alice","R");roomId=user(a).getCurrentRoomId();if(!mode.equals("noHand")){ready(a,"HA");if(!mode.equals("ready"))roomService.cancelReady(a,roomId.toString());}}
        var u=user(a);var hand=u.getOriginalHand();var id=u.getId();Instant before=u.getLastSeenAt();
        String page=List.of("result","normal").contains(mode)?"/round-result":List.of("selecting","confirmed").contains(mode)?"/play":"/room";
        var model=mvc.perform(get(page).session(a)).andReturn().getModelAndView().getModel();assertTrue(model.containsKey("roomActionForm"));
        mvc.perform(post("/room/leave").session(a).param("roomId",roomId.toString())).andExpect(redirectedUrl("/rooms"));
        assertSame(u,user(a));assertEquals(id,user(a).getId());assertEquals("Alice",u.getUsername());assertSame(hand,u.getOriginalHand());assertEquals(before,u.getLastSeenAt());
        mvc.perform(get("/api/status").session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.userState").value("ROOM_NONE"));
        mvc.perform(post("/room/leave").session(a).param("roomId",roomId.toString())).andExpect(status().isConflict());
        if(matchId!=null){var m=matches.findById(matchId).orElseThrow();assertFalse(m.getParticipants().get(id).isActive());
            if(mode.equals("normal")){assertEquals(MatchEndType.NORMAL,m.getPendingEndType());assertTrue(m.getPendingWinnerIds().contains(id));}else assertNull(m.getEndType());}
        mvc.perform(post("/rooms/enter").session(a).param("roomName","Different")).andExpect(status().isFound());assertSame(hand,u.getOriginalHand());
    }
    @ParameterizedTest @CsvSource({"2,selecting", "3,selecting", "3,confirmed", "3,remainingConfirmed", "2,result", "3,result", "2,draw", "3,draw"})
    void tc125LogoutDepartureMatrix(int count,String mode) throws Exception {
        start(3,count==3);var u=user(a);var id=u.getId();
        if(mode.equals("confirmed"))submit(a,"ROCK");
        if(mode.equals("remainingConfirmed")){submit(b,"ROCK");submit(c,"SCISSORS");}
        if(mode.equals("result")||mode.equals("draw")){submit(a,"ROCK");submit(b,mode.equals("draw")?"ROCK":"SCISSORS");if(count==3)submit(c,mode.equals("draw")?"ROCK":"SCISSORS");}
        var m=matches.findById(matchId).orElseThrow();var history=List.copyOf(m.getRoundHistory());var deadline=m.getTransitionAt();
        var scores=m.getParticipants().values().stream().map(MatchParticipant::getScore).toList();
        mvc.perform(post("/logout").session(a)).andExpect(redirectedUrl("/"));assertTrue(a.isInvalid());assertTrue(users.findById(id).isEmpty());assertFalse(m.getParticipants().get(id).isActive());
        assertFalse(rooms.findById(roomId).orElseThrow().getMemberIds().contains(id));assertEquals(user(b).getId(),rooms.findById(roomId).orElseThrow().getHostUserId());
        if(count==2){assertEquals(MatchEndType.ABORTED,m.getEndType());assertTrue(m.getWinnerIds().isEmpty());assertEquals(history,m.getRoundHistory());assertEquals(scores,m.getParticipants().values().stream().map(MatchParticipant::getScore).toList());}
        else if(mode.equals("remainingConfirmed")){assertEquals(1,m.getRoundHistory().size());assertEquals(1,m.getParticipants().get(user(b).getId()).getScore());}
        else if(mode.equals("result")||mode.equals("draw")){assertEquals(history,m.getRoundHistory());assertEquals(deadline,m.getTransitionAt());assertEquals(scores,m.getParticipants().values().stream().map(MatchParticipant::getScore).toList());}
        else {assertEquals(MatchState.SELECTING_HAND,m.getState());assertFalse(m.getCurrentRound().getSelections().containsKey(id));}
    }
    @Test void tc080ResponsiveCssAndWinnerLabelsAreServed() throws Exception {
        var response=http(client(),"/css/style.css",null);assertEquals(200,response.statusCode());String css=response.body();
        assertTrue(css.contains("@media (max-width: 768px)"));assertTrue(css.contains("overflow-x: auto"));assertTrue(css.contains("min-width: 580px"));assertTrue(css.contains("flex-wrap: wrap"));
        assertTrue(css.contains(".round-winner"));assertTrue(css.contains("font-weight: bold"));
        // 表の全列と文字の勝利表示はHTMLテストでも確認し、CSSだけを合否根拠にしない。
        normal();var s=client();http(s,"/login","username=Viewer");http(s,"/rooms/enter","roomName=R");
        var html=http(s,"/match-result?matchId="+matchId,null).body();assertTrue(html.contains("table-scroll"));assertTrue(html.contains("他のオリジナル手"));assertTrue(html.contains("グー"));assertTrue(html.contains("チョキ"));assertTrue(html.contains("パー"));
    }
    @ParameterizedTest @ValueSource(strings={"username","handName","both"})
    void tc027PlayingDuplicateNamesDoNotBlockSpectatorReady(String conflict) throws Exception {
        start(3,true);var old=matches.findById(matchId).orElseThrow();
        var spectator=login(conflict.equals("handName")?"Spectator":"Alice","R");
        ready(spectator,conflict.equals("username")?"DifferentHand":"HA");
        assertEquals(UserState.READY,user(spectator).getState());assertEquals(3,old.getParticipants().size());
        assertFalse(old.getParticipants().containsKey(user(spectator).getId()));assertEquals(MatchState.SELECTING_HAND,old.getState());
    }
}