package com.example.janken.controller;

import com.example.janken.domain.enums.UserState;
import com.example.janken.form.RoomRuleForm;
import com.example.janken.service.*;
import com.example.janken.store.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageNineWebTests {
    @LocalServerPort int port;
    @Autowired AuthController auth;
    @Autowired RoomController room;
    @Autowired HtmlErrorHandler errors;
    @Autowired GameController game;
    @Autowired OriginalHandController hands;
    @Autowired MatchStore matches;
    @Autowired GameStateLock lock;
    @Autowired UserStore users;
    @Autowired RoomStore rooms;
    MockMvc mvc;
    @BeforeEach void setup() {
        synchronized (lock) {
            matches.findAll().forEach(m -> matches.deleteById(m.getId()));
            rooms.findAll().forEach(r -> rooms.deleteById(r.getId()));
            users.findAll().forEach(u -> users.deleteById(u.getId()));
        }
        mvc = MockMvcBuilders.standaloneSetup(auth, room, game, hands).setControllerAdvice(errors)
                .setViewResolvers((name, locale) -> name.startsWith("redirect:") ? new RedirectView(name.substring(9)) : new View() {
                    public void render(Map<String, ?> model, jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response) { }
                }).build();
    }
    MockHttpSession session(String name) throws Exception {
        var s = (MockHttpSession) mvc.perform(post("/login").param("username", name)).andReturn().getRequest().getSession(false);
        mvc.perform(post("/rooms/enter").session(s).param("roomName", "R")).andExpect(status().isFound()); return s;
    }
    String id(MockHttpSession s) throws Exception {
        return (String) mvc.perform(get("/room").session(s)).andReturn().getModelAndView().getModel().get("roomId");
    }
    HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    HttpResponse<String> http(HttpClient client, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (body == null) { builder.GET(); }
        else { builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void redirect(HttpResponse<String> response, String path) {
        assertEquals(302, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("location").orElseThrow().endsWith(path));
    }
    String hiddenRoom(String html) {
        var matcher = java.util.regex.Pattern.compile("name=\"roomId\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), html); return matcher.group(1);
    }
    String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    String saveBody(String id, String name) {
        return "returnPage=ROOM&roomId=" + id + "&name=" + encode(name) + "&vsRock=LOSE&vsScissors=WIN&vsPaper=DRAW&vsOriginal=LOSE";
    }
    void createAndReady(HttpClient client, String id, String name) throws Exception {
        redirect(http(client, "/original-hand/save", saveBody(id, name)), "/room");
        redirect(http(client, "/room/ready", "roomId=" + id), "/room");
    }


    String[] startHttp(HttpClient a,HttpClient b) throws Exception {
        redirect(http(a,"/login","username=Alice"),"/rooms"); redirect(http(b,"/login","username=Bob"),"/rooms");
        redirect(http(a,"/rooms/enter","roomName=R"),"/room"); redirect(http(b,"/rooms/enter","roomName=R"),"/room");
        String roomId=hiddenRoom(http(a,"/room",null).body()); createAndReady(a,roomId,"HA"); createAndReady(b,roomId,"HB");
        var started=http(a,"/room/start","roomId="+roomId); String matchId;
        synchronized(lock){ matchId=rooms.findById(UUID.fromString(roomId)).orElseThrow().getCurrentMatchId().toString(); }
        redirect(started,"/play?matchId="+matchId); return new String[]{roomId,matchId};
    }
    String normalBody(String matchId,String hand) {return "matchId="+matchId+"&roundNumber=1&type=NORMAL&normalHand="+hand;}
    @Test void realHttpSubmitDuplicateAndFinalState() throws Exception {
        var a=browser(); var b=browser(); String mid=startHttp(a,b)[1]; String play="/play?matchId="+mid;
        String initial=http(a,play,null).body(); assertTrue(initial.contains("action=\"/play\"")); assertEquals(6,count(initial,"<button")); // 手選択5個＋第11段階の履歴開閉。 assertFalse(initial.contains("disabled"));
        redirect(http(a,"/play",normalBody(mid,"ROCK")),play);
        String self=http(a,play,null).body(); assertTrue(self.contains("確定済みの手：<strong>グー</strong>")); assertTrue(self.contains("他の参加者の確定を待っています")); assertEquals(5,count(self,"disabled=\"disabled\""));
        String other=http(b,play,null).body(); assertFalse(other.contains("確定済みの手：")); assertFalse(other.contains("disabled"));
        for(String hand:List.of("ROCK","PAPER","bad")) {var res=http(a,"/play",normalBody(mid,hand)); assertEquals(409,res.statusCode()); assertTrue(res.body().contains("INVALID_STATE"));}
        redirect(http(b,"/play",normalBody(mid,"SCISSORS")),play);
        synchronized(lock) {
            var m=matches.findById(UUID.fromString(mid)).orElseThrow(); assertEquals(com.example.janken.domain.enums.MatchState.ROUND_RESULT,m.getState()); assertEquals(1,m.getRoundHistory().size());
            var result=m.getRoundHistory().getFirst(); assertEquals(result.getDecidedAt().plusSeconds(10),m.getTransitionAt());
            assertEquals(List.of(1,0),m.getParticipants().values().stream().map(com.example.janken.domain.MatchParticipant::getScore).toList());
            assertEquals(List.of("グー","チョキ"),result.getEntries().stream().map(com.example.janken.domain.RoundResultEntry::getHandName).toList());
        }
        assertEquals(409,http(b,"/play",normalBody(mid,"bad")).statusCode());
        // ROUND_RESULT後のGET表示は第11段階の統合対象なので、ここでは要求しない。
    }
    int count(String text,String part){ return (text.length()-text.replace(part,"").length())/part.length(); }
    @Test void realHttpSelectsOtherOwnersSnapshotAndKeepsRelationsPrivate() throws Exception {
        var a=browser(); var b=browser(); String mid=startHttp(a,b)[1]; UUID id;
        synchronized(lock){ id=matches.findById(UUID.fromString(mid)).orElseThrow().getOriginalHands().get(1).getHandId(); }
        redirect(http(a,"/play","matchId="+mid+"&roundNumber=1&type=ORIGINAL&originalHandId="+id),"/play?matchId="+mid);
        String self=http(a,"/play?matchId="+mid,null).body(); assertTrue(self.contains("確定済みの手：<strong>HB</strong>"));
        String other=http(b,"/play?matchId="+mid,null).body(); assertFalse(other.contains("確定済みの手："));
        for(String html:List.of(self,other)){for(String forbidden:List.of("vsRock","vsScissors","vsPaper","vsOriginal","selectedHand","/api/status","/round-result")){assertFalse(html.contains(forbidden),forbidden);}}
    }
    @ParameterizedTest @ValueSource(strings={"ROOM_NONE","ROOM_WAITING","READY"})
    void postOnlyPlayingThroughController(String state) throws Exception {
        var s=session("Alice"); synchronized(lock){var u=users.findAll().getFirst();u.setState(UserState.valueOf(state));if(state.equals("ROOM_NONE")){u.setCurrentRoomId(null);}}
        mvc.perform(post("/play").session(s).param("type","invalid")).andExpect(status().isConflict()).andExpect(model().attribute("errorCode","INVALID_STATE"));
    }
    @Test void anonymousPost401() throws Exception {mvc.perform(post("/play")).andExpect(status().isUnauthorized()).andExpect(model().attribute("errorCode","LOGIN_REQUIRED"));}
    @ParameterizedTest @ValueSource(strings={"duplicate","lastTwo","lastDuplicate"})
    void simultaneousHttpPosts(String kind) throws Exception {
        var a=browser(); var b=browser(); String mid=startHttp(a,b)[1];
        if(kind.equals("lastDuplicate")){redirect(http(a,"/play",normalBody(mid,"ROCK")),"/play?matchId="+mid);}
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); var gate=new java.util.concurrent.CyclicBarrier(2);
        try{
            var fa=pool.submit(()->{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);return http(kind.equals("lastDuplicate")?b:a,"/play",normalBody(mid,kind.equals("lastDuplicate")?"SCISSORS":"ROCK")).statusCode();});
            var fb=pool.submit(()->{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);return http(kind.equals("duplicate")?a:b,"/play",normalBody(mid,kind.equals("duplicate")?"PAPER":"SCISSORS")).statusCode();});
            var codes=List.of(fa.get(10,java.util.concurrent.TimeUnit.SECONDS),fb.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(kind.equals("lastTwo")?0:1,Collections.frequency(codes,409)); assertEquals(kind.equals("lastTwo")?2:1,Collections.frequency(codes,302));
            synchronized(lock){var m=matches.findById(UUID.fromString(mid)).orElseThrow(); assertEquals(kind.equals("duplicate")?1:2,m.getCurrentRound().getSelections().size()); assertEquals(kind.equals("duplicate")?0:1,m.getRoundHistory().size()); if(!kind.equals("duplicate")){assertEquals(List.of(1,0),m.getParticipants().values().stream().map(com.example.janken.domain.MatchParticipant::getScore).toList());}}
        }finally{pool.shutdownNow();}
    }
    @Test void rawInputErrorsAndStaleTargetsThroughHttp() throws Exception {
        var a=browser(); var b=browser(); String mid=startHttp(a,b)[1];
        for(String body:List.of("", "matchId=bad&roundNumber=1", "matchId="+mid+"&roundNumber=bad",normalBody(mid,"bad"),"matchId="+mid+"&roundNumber=1&type=ORIGINAL&originalHandId=bad")){
            var res=http(a,"/play",body);assertEquals(400,res.statusCode(),res.body());assertTrue(res.body().contains("VALIDATION_ERROR"));
        }
        for(String body:List.of(normalBody(UUID.randomUUID().toString(),"bad"),"matchId="+mid+"&roundNumber=2&type=bad")){
            var res=http(a,"/play",body);assertEquals(409,res.statusCode());assertTrue(res.body().contains("INVALID_STATE"));
        }
        synchronized(lock){assertTrue(matches.findById(UUID.fromString(mid)).orElseThrow().getCurrentRound().getSelections().isEmpty());}
    }
}
