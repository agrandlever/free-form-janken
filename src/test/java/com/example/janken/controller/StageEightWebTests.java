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
class StageEightWebTests {
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

    @ParameterizedTest @ValueSource(booleans={true,false})
    void realHttpStartFlowAndParticipantAccess(boolean hostReady) throws Exception {
        var host=browser(); var a=browser(); var b=browser();
        for(var pair:List.of(Map.entry(host,"Host"),Map.entry(a,"Alice"),Map.entry(b,"Bob"))) {
            redirect(http(pair.getKey(),"/login","username="+pair.getValue()),"/rooms");
            redirect(http(pair.getKey(),"/rooms/enter","roomName=R"),"/room");
        }
        String id=hiddenRoom(http(host,"/room",null).body());
        assertFalse(http(host,"/room",null).body().contains("action=\"/room/start\""));
        createAndReady(a,id,"HA"); createAndReady(b,id,"HB");
        if(hostReady) { createAndReady(host,id,"HH"); }
        assertTrue(http(host,"/room",null).body().contains("action=\"/room/start\""));
        assertFalse(http(a,"/room",null).body().contains("action=\"/room/start\""));
        redirect(http(a,"/play",null),"/room");
        redirect(http(host,"/room/rules","roomId="+id+"&targetWins=5&preventConsecutiveSameOriginalHand=true"),"/room");
        var start=http(host,"/room/start","roomId="+id); String matchId;
        synchronized(lock) { matchId=rooms.findById(UUID.fromString(id)).orElseThrow().getCurrentMatchId().toString(); }
        String play="/play?matchId="+matchId;
        UUID foreignMatchId=UUID.randomUUID();
        synchronized(lock) {
            matches.save(new com.example.janken.domain.GameMatch(foreignMatchId,UUID.randomUUID(),"ForeignRoom",99,false));
        }
        redirect(http(a,"/play?matchId="+foreignMatchId,null),play);
        synchronized(lock) { matches.deleteById(foreignMatchId); }
        redirect(start,hostReady?play:"/room");
        for(var client:hostReady?List.of(host,a,b):List.of(a,b)) {
            for(var path:List.of("/","/rooms","/room")) { redirect(http(client,path,null),play); }
            redirect(http(client,"/play?matchId="+UUID.randomUUID(),null),play);
            redirect(http(client,"/play?matchId=invalid",null),play);
            for(var path:List.of("/play",play)) {
                var response=http(client,path,null); assertEquals(200,response.statusCode(),response.body());
                String html=response.body(); assertTrue(html.contains("第<span>1</span>ラウンド"));
                assertTrue(html.contains("先取勝数：<span>5</span>")); assertTrue(html.contains("現在の勝数：<span>0</span>"));
                assertTrue(html.contains("あなたは対戦参加者です。")); assertTrue(html.contains("HA")); assertTrue(html.contains("HB"));
                assertTrue(html.contains("Alice")); assertTrue(html.contains("Bob")); assertTrue(html.contains("ON"));
                for(var forbidden:List.of("/room/leave","/logout","/api/status","vsRock","/original-hand/save")) {
                    assertFalse(html.contains(forbidden),forbidden);
                }
            }
        }
        if(!hostReady) {
            String html=http(host,"/room",null).body(); assertTrue(html.contains("対戦が進行中です。"));
            assertTrue(html.contains("現在対戦中")); assertFalse(html.contains("/room/start")); assertFalse(html.contains("対戦を見る"));
            redirect(http(host,play,null),"/room");
            // 開始後に③になっても、今回の段階では観戦者として通さない。
            createAndReady(host,id,"HH"); redirect(http(host,play,null),"/room");
        }
        for(var path:List.of("/room/start","/room/ready","/room/ready/cancel","/room/rules","/room/leave","/logout","/original-hand/save","/original-hand/delete")) {
            String body=path.startsWith("/original-hand")?saveBody(id,"Changed"):"roomId="+id+"&targetWins=9";
            var response=http(hostReady?host:a,path,body);
            // 非ホストのstart/rulesは状態判定よりホスト権限確認が先。
            int expected=!hostReady && (path.equals("/room/start")||path.equals("/room/rules"))?403:409;
            assertEquals(expected,response.statusCode(),response.body());
            assertTrue(response.body().contains(expected==403?"FORBIDDEN":"INVALID_STATE"));
        }
        synchronized(lock) { assertEquals(1,matches.findAll().size()); assertEquals(matchId,matches.findAll().getFirst().getId().toString()); }
        assertEquals(400,http(a,"/play","matchId="+matchId).statusCode());
    }
    @Test void getPlayRedirectsAnonymousAndNonParticipants() throws Exception {
        var client=browser(); redirect(http(client,"/play",null),"/");
        redirect(http(client,"/login","username=Alone"),"/rooms"); redirect(http(client,"/play",null),"/rooms");
        redirect(http(client,"/rooms/enter","roomName=R"),"/room"); redirect(http(client,"/play?matchId="+UUID.randomUUID(),null),"/room");
    }
    @Test void startErrorsAtControllerBoundary() throws Exception {
        mvc.perform(post("/room/start")).andExpect(status().isUnauthorized()).andExpect(model().attribute("errorCode","LOGIN_REQUIRED"));
        var host=session("Host"); var other=session("Other"); String id=id(host);
        mvc.perform(post("/room/start").session(other).param("roomId",id)).andExpect(status().isForbidden()).andExpect(model().attribute("errorCode","FORBIDDEN"));
        for(String target:List.of(id,"bad",UUID.randomUUID().toString())) {
            mvc.perform(post("/room/start").session(host).param("roomId",target)).andExpect(status().isConflict()).andExpect(model().attribute("errorCode","INVALID_STATE"));
        }
        assertTrue(matches.findAll().isEmpty());
    }
}
