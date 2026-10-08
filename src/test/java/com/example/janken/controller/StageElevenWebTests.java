package com.example.janken.controller;

import com.example.janken.domain.enums.UserState;
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
class StageElevenWebTests {
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
        mvc = MockMvcBuilders.standaloneSetup(auth, room, game, hands, api).setControllerAdvice(errors)
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

    @Autowired StatusApiController api;
    @Test void anonymousStatusIs401Json() throws Exception {
        var response=http(browser(),"/api/status",null);
        assertEquals(401,response.statusCode());assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("application/json"));
        var json=json(response);
        assertEquals("LOGIN_REQUIRED",json.get("code").asString());assertTrue(json.get("errors").isArray());
        assertFalse(response.body().contains("<html"));assertTrue(response.headers().firstValue("location").isEmpty());
    }
    tools.jackson.databind.JsonNode json(HttpResponse<String> response) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
    }
    @Test void roomNoneJsonHasExactContractAndNulls() throws Exception {
        var a=browser();redirect(http(a,"/login","username=Alice"),"/rooms");
        var response=http(a,"/api/status",null);assertEquals(200,response.statusCode());var j=json(response);
        assertEquals(Set.of("serverTime","userState","currentRoomId","roomState","currentMatchId","matchState","roundNumber",
                "transitionAt","lastCompletedMatchId","displayMatchId","displayMatchState","displayRoundNumber",
                "displayTransitionAt","selfHandConfirmed","selfSelectedHand","room"), j.propertyNames());
        assertEquals("ROOM_NONE",j.get("userState").asString());assertEquals("NONE",j.get("roomState").asString());
        java.time.Instant.parse(j.get("serverTime").asString());
        for(String key:j.propertyNames()){if(!List.of("serverTime","userState","roomState").contains(key))assertTrue(j.get(key).isNull(),key);}
    }
    @ParameterizedTest @ValueSource(strings={"","bad","1-1-1-1-1"})
    void malformedStatus400JsonAndNoHeartbeat(String id) throws Exception {
        var a=browser();var b=browser();startHttp(a,b);java.time.Instant old;
        synchronized(lock){var u=users.findAll().stream().filter(x->x.getUsername().equals("Alice")).findFirst().orElseThrow();u.setLastSeenAt(java.time.Instant.EPOCH);old=u.getLastSeenAt();}
        var response=http(a,"/api/status?matchId="+id,null);
        assertEquals(400,response.statusCode());var j=json(response);
        assertEquals(Set.of("code","message","errors"),j.propertyNames());assertEquals("VALIDATION_ERROR",j.get("code").asString());
        assertEquals("matchId",j.get("errors").get(0).get("field").asString());assertFalse(j.has("selfHandConfirmed"));
        synchronized(lock){assertEquals(old,users.findAll().stream().filter(x->x.getUsername().equals("Alice")).findFirst().orElseThrow().getLastSeenAt());}
    }
    @ParameterizedTest @ValueSource(strings={"ROCK","SCISSORS","PAPER","ORIGINAL"})
    void realHttpSelfOnlyFixedHandAndIdentity(String type) throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];String path="/api/status?matchId="+mid;
        var initial=json(http(a,path,null));assertFalse(initial.get("selfHandConfirmed").asBoolean());assertTrue(initial.get("selfSelectedHand").isNull());
        UUID otherId;UUID originalId;
        synchronized(lock){var m=matches.findById(UUID.fromString(mid)).orElseThrow();otherId=m.getParticipants().keySet().stream().skip(1).findFirst().orElseThrow();originalId=m.getOriginalHands().getLast().getHandId();}
        String body=type.equals("ORIGINAL")?"matchId="+mid+"&roundNumber=1&type=ORIGINAL&originalHandId="+originalId:normalBody(mid,type);
        redirect(http(a,"/play",body)," /play?matchId=".strip()+mid);
        var response=http(a,path+"&userId="+otherId+"&submittedUserId="+otherId,null);var j=json(response);
        assertTrue(j.get("selfHandConfirmed").asBoolean());var h=j.get("selfSelectedHand");
        assertEquals(Set.of("type","normalHand","originalHandId","handName"),h.propertyNames());
        assertEquals(type.equals("ORIGINAL")?"ORIGINAL":"NORMAL",h.get("type").asString());
        assertEquals(type.equals("ORIGINAL")?"HB":switch(type){case "ROCK"->"グー";case "SCISSORS"->"チョキ";default->"パー";},h.get("handName").asString());
        if(type.equals("ORIGINAL")){assertTrue(h.get("normalHand").isNull());assertEquals(originalId.toString(),h.get("originalHandId").asString());}
        else{assertEquals(type,h.get("normalHand").asString());assertTrue(h.get("originalHandId").isNull());}
        var other=json(http(b,path,null));assertFalse(other.get("selfHandConfirmed").asBoolean());assertTrue(other.get("selfSelectedHand").isNull());
        for(String secret:List.of("selections","vsRock","vsScissors","vsPaper","vsOriginal"))assertFalse(response.body().contains(secret));
        var memberKeys=j.get("room").get("members").get(0).propertyNames();
        assertEquals(Set.of("userId","username","userState","isHost"),memberKeys);
        assertEquals(409,http(a,"/play",body).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"none","unknown","foreign"})
    void invalidDisplayDoesNotLeak(String kind) throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];String query="";
        if(kind.equals("unknown"))query="?matchId="+UUID.randomUUID();
        if(kind.equals("foreign"))synchronized(lock){var m=new com.example.janken.domain.GameMatch(UUID.randomUUID(),UUID.randomUUID(),"SECRET",3,false);m.setCurrentRound(new com.example.janken.domain.Round(99,java.time.Instant.EPOCH));matches.save(m);query="?matchId="+m.getId();}
        var response=http(a,"/api/status"+query,null);var j=json(response);
        assertEquals(mid,j.get("currentMatchId").asString());
        for(String key:List.of("displayMatchId","displayMatchState","displayRoundNumber","displayTransitionAt","selfHandConfirmed","selfSelectedHand"))assertTrue(j.get(key).isNull(),key);
        assertFalse(response.body().contains("SECRET"));
    }
    @ParameterizedTest @ValueSource(strings={"ROOM_WAITING","READY"})
    void roomJsonWaitingAndReady(String state) throws Exception {
        var a=browser();var b=browser();String[] ids=startHttp(a,b);
        synchronized(lock){rooms.findById(UUID.fromString(ids[0])).orElseThrow().setCurrentMatchId(null);users.findAll().forEach(u->u.setState(UserState.valueOf(state)));}
        var j=json(http(a,"/api/status",null));assertEquals(state,j.get("userState").asString());assertEquals("WAITING",j.get("roomState").asString());
        assertTrue(j.get("currentMatchId").isNull());assertEquals(2,j.get("room").get("members").size());
        assertEquals(Set.of("id","name","hostUserId","targetWins","preventConsecutiveSameOriginalHand","members"),j.get("room").propertyNames());
        assertEquals("Alice",j.get("room").get("members").get(0).get("username").asString());
        assertTrue(j.get("room").get("members").get(0).get("isHost").asBoolean());
    }
    @ParameterizedTest @CsvSource({"ROCK,SCISSORS,false,10","ROCK,ROCK,false,5","ROCK,SCISSORS,true,10"})
    void realHttpRoundResultAndDeadline(String handA,String handB,boolean finalRound,int seconds) throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];
        synchronized(lock){if(finalRound)matches.findById(UUID.fromString(mid)).orElseThrow().getParticipants().values().forEach(p->p.setScore(2));}
        redirect(http(a,"/play",normalBody(mid,handA)),"/play?matchId="+mid);
        redirect(http(b,"/play",normalBody(mid,handB)),"/play?matchId="+mid);
        redirect(http(a,"/play?matchId="+mid,null),"/round-result?matchId="+mid);
        String html=http(a,"/round-result?matchId="+mid,null).body();
        assertTrue(html.contains("第<span>1</span>ラウンド結果"));assertTrue(html.contains("Alice"));assertTrue(html.contains("Bob"));assertTrue(html.contains("グー"));
        assertEquals(handA.equals(handB),html.contains("このラウンドの勝者はいません"));
        assertEquals(!handA.equals(handB),html.contains(">勝利</strong>"));
        assertTrue(html.contains(finalRound?"対戦結果まで":"次のラウンドまで"));
        assertTrue(html.contains("ルームを退出"));assertFalse(html.contains("ルームへ戻る"));
        assertFalse(html.contains("vsRock"));assertFalse(html.contains("vsOriginal"));
        var j=json(http(a,"/api/status?matchId="+mid,null));assertEquals("ROUND_RESULT",j.get("matchState").asString());
        assertTrue(j.get("selfHandConfirmed").isNull());assertTrue(j.get("selfSelectedHand").isNull());
        synchronized(lock){var m=matches.findById(UUID.fromString(mid)).orElseThrow();assertEquals(m.getRoundHistory().getFirst().getDecidedAt().plusSeconds(seconds).toString(),j.get("transitionAt").asString());}
    }
    @ParameterizedTest @ValueSource(strings={"none","unknown","foreign","malformed"})
    void roundResultDirectAccessAlwaysUsesCurrent(String kind) throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];String query="";
        if(kind.equals("unknown"))query="?matchId="+UUID.randomUUID();
        if(kind.equals("malformed"))query="?matchId=bad";
        if(kind.equals("foreign"))synchronized(lock){var m=new com.example.janken.domain.GameMatch(UUID.randomUUID(),UUID.randomUUID(),"SECRET",3,false);matches.save(m);query="?matchId="+m.getId();}
        redirect(http(a,"/round-result"+query,null),"/play?matchId="+mid);
        http(a,"/play",normalBody(mid,"ROCK"));http(b,"/play",normalBody(mid,"SCISSORS"));
        if(query.isEmpty()){assertEquals(200,http(a,"/round-result",null).statusCode());}
        else redirect(http(a,"/round-result"+query,null),"/round-result?matchId="+mid);
        assertFalse(http(a,"/round-result?matchId="+mid,null).body().contains("SECRET"));
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","none","waiting","ready"})
    void nonParticipantsUseSpectatorAccessRules(String state) throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];var visitor=browser();
        if(!state.equals("anonymous"))http(visitor,"/login","username=Visitor");
        if(state.equals("waiting")||state.equals("ready")){http(visitor,"/rooms/enter","roomName=R");if(state.equals("ready")){String rid=hiddenRoom(http(visitor,"/room",null).body());createAndReady(visitor,rid,"VisitorHand");}}
        redirect(http(visitor,"/round-result?matchId="+mid,null),state.equals("anonymous")?"/":state.equals("none")?"/rooms":"/play?matchId="+mid);
    }
    @Test void historyHtmlMultipleWinnersNoScoresAndNoWinner() throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];
        synchronized(lock){
            var m=matches.findById(UUID.fromString(mid)).orElseThrow();var ps=m.getParticipants().values().stream().toList();
            m.getRoundHistory().add(new com.example.janken.domain.RoundResult(1,ps.stream().map(p->new com.example.janken.domain.RoundResultEntry(p.getUserId(),p.getUsername(),"履歴手",true)).toList(),true,java.time.Instant.EPOCH));
            m.getRoundHistory().add(new com.example.janken.domain.RoundResult(2,ps.stream().map(p->new com.example.janken.domain.RoundResultEntry(p.getUserId(),p.getUsername(),"パー",false)).toList(),false,java.time.Instant.EPOCH));
            ps.forEach(p->p.setScore(7));m.setCurrentRound(new com.example.janken.domain.Round(3,java.time.Instant.EPOCH));
        }
        String html=http(a,"/play?matchId="+mid,null).body();
        String history=html.substring(html.indexOf("<div id=\"round-history\""),html.indexOf("<h2>手を選択</h2>"));
        assertTrue(history.contains("hidden"));assertTrue(html.contains("ラウンド履歴を表示"));assertTrue(history.contains("第<span>1</span>ラウンド"));assertTrue(history.contains("第<span>2</span>ラウンド"));
        assertTrue(history.contains("Alice"));assertTrue(history.contains("Bob"));assertTrue(history.contains("履歴手"));assertEquals(2,count(history,">勝利</strong>"));
        assertTrue(history.contains("このラウンドの勝者はいません"));assertFalse(history.contains("7"));assertFalse(history.contains("score"));assertFalse(history.contains("vsOriginal"));
        assertTrue(html.contains("<span>7</span>勝"));assertTrue(html.contains("/js/play.js"));assertTrue(html.contains("/js/status-polling.js"));
    }
    @Test void roundResultModelThroughMvc() throws Exception {
        var a=session("Alice");var b=session("Bob");String rid=id(a);
        for(var entry:Map.of(a,"HA",b,"HB").entrySet()){var s=entry.getKey();mvc.perform(post("/original-hand/save").session(s).param("returnPage","ROOM").param("roomId",rid).param("name",entry.getValue()).param("vsRock","LOSE").param("vsScissors","WIN").param("vsPaper","DRAW").param("vsOriginal","LOSE"));mvc.perform(post("/room/ready").session(s).param("roomId",rid));}
        mvc.perform(post("/room/start").session(a).param("roomId",rid));
        String mid; synchronized(lock){mid=rooms.findById(UUID.fromString(rid)).orElseThrow().getCurrentMatchId().toString();}
        mvc.perform(post("/play").session(a).param("matchId",mid).param("roundNumber","1").param("type","NORMAL").param("normalHand","ROCK"));
        mvc.perform(post("/play").session(b).param("matchId",mid).param("roundNumber","1").param("type","NORMAL").param("normalHand","SCISSORS"));
        mvc.perform(get("/round-result").session(a).param("matchId",mid)).andExpect(status().isOk()).andExpect(view().name("round-result"))
                .andExpect(model().attributeExists("roomId","matchId","roundNumber","results","scores","hasRoundWinner","transitionAt","matchFinished","userState"))
                .andExpect(model().attribute("matchFinished",false)).andExpect(model().attribute("hasRoundWinner",true));
    }
    @Test void futureRoundSelectingCanonicalGetUsesNewModel() throws Exception {
        var a=browser();var b=browser();String mid=startHttp(a,b)[1];
        http(a,"/play",normalBody(mid,"ROCK"));http(b,"/play",normalBody(mid,"ROCK"));
        synchronized(lock){var m=matches.findById(UUID.fromString(mid)).orElseThrow();m.setCurrentRound(new com.example.janken.domain.Round(2,java.time.Instant.now()));m.setState(com.example.janken.domain.enums.MatchState.SELECTING_HAND);m.setTransitionAt(null);}
        redirect(http(a,"/round-result?matchId="+mid,null),"/play?matchId="+mid);
        var j=json(http(a,"/api/status?matchId="+mid,null));assertEquals(2,j.get("displayRoundNumber").asInt());assertFalse(j.get("selfHandConfirmed").asBoolean());
        assertTrue(http(a,"/play?matchId="+mid,null).body().contains("data-round-number=\"2\""));
    }
    int count(String text,String part){return (text.length()-text.replace(part,"").length())/part.length();}
}
