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
class StageSixWebTests {
    @LocalServerPort int port;
    @Autowired AuthController auth;
    @Autowired RoomController room;
    @Autowired HtmlErrorHandler errors;
    @Autowired GameStateLock lock;
    @Autowired UserStore users;
    @Autowired RoomStore rooms;
    MockMvc mvc;
    @BeforeEach void setup() {
        synchronized (lock) {
            rooms.findAll().forEach(r -> rooms.deleteById(r.getId()));
            users.findAll().forEach(u -> users.deleteById(u.getId()));
        }
        mvc = MockMvcBuilders.standaloneSetup(auth, room).setControllerAdvice(errors)
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
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"0","100","abc","1.5"})
    void invalidRulesRebuildFormAndRoomModel(String value) throws Exception {
        var s = session("Host"); String id = id(s);
        var request = post("/room/rules").session(s).param("roomId", id).param("preventConsecutiveSameOriginalHand", "true");
        if (value != null) { request.param("targetWins", value); }
        var result = mvc.perform(request).andExpect(status().isBadRequest()).andExpect(view().name("room"))
                .andExpect(model().attribute("errorCode", "VALIDATION_ERROR"))
                .andExpect(model().attributeExists("errorMessages", "fieldErrors", "form", "roomRuleForm", "roomName", "members", "readyMembers", "roomActionForm", "originalHandForm"))
                .andExpect(model().attribute("targetWins", 3)).andExpect(model().attribute("preventConsecutiveSameOriginalHand", false))
                .andReturn();
        var model = result.getModelAndView().getModel();
        var form = (RoomRuleForm) model.get("roomRuleForm"); assertEquals(value, form.getTargetWins());
        assertEquals(id, form.getRoomId()); assertTrue(form.isPreventConsecutiveSameOriginalHand());
        assertTrue(((Map<?, ?>) model.get("fieldErrors")).containsKey("targetWins"));
    }
    @Test void authorizationPrecedesInvalidIntegerAtControllerBoundary() throws Exception {
        session("Host"); var b = session("Member");
        mvc.perform(post("/room/rules").session(b).param("roomId", id(b)).param("targetWins", "abc"))
                .andExpect(status().isForbidden()).andExpect(model().attribute("errorCode", "FORBIDDEN"));
        mvc.perform(post("/room/rules").session(b).param("roomId", UUID.randomUUID().toString()).param("targetWins", "abc"))
                .andExpect(status().isConflict()).andExpect(model().attribute("errorCode", "INVALID_STATE"));
    }
    @Test void invalidBooleanAlsoPreservesAuthorizationPriority() throws Exception {
        var a = session("Host"); var b = session("Member");
        mvc.perform(post("/room/rules").session(b).param("roomId", id(b)).param("targetWins", "5")
                .param("preventConsecutiveSameOriginalHand", "bad"))
                .andExpect(status().isForbidden()).andExpect(model().attribute("errorCode", "FORBIDDEN"));
        mvc.perform(post("/room/rules").session(a).param("roomId", id(a)).param("targetWins", "5")
                .param("preventConsecutiveSameOriginalHand", "bad"))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("errorCode", "VALIDATION_ERROR"))
                .andExpect(model().attribute("targetWins", 3));
    }
    @Test void anonymousNewPostsAndInvalidReadyRequests() throws Exception {
        for (String path : List.of("/room/ready", "/room/ready/cancel", "/room/rules")) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized()).andExpect(model().attribute("errorCode", "LOGIN_REQUIRED"));
        }
        var s = session("A");
        mvc.perform(post("/room/ready").session(s).param("roomId", id(s))).andExpect(status().isConflict())
                .andExpect(model().attribute("errorCode", "ORIGINAL_HAND_REQUIRED"));
        mvc.perform(post("/room/ready/cancel").session(s).param("roomId", id(s))).andExpect(status().isConflict())
                .andExpect(model().attribute("errorCode", "INVALID_STATE"));
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
    void readyUi(String html) {
        assertTrue(html.contains("準備取消")); assertFalse(html.contains("action=\"/room/ready\""));
        assertTrue(html.contains("（準備完了）"));
        for (String text : List.of("オリジナル手を作成", "オリジナル手を編集", "オリジナル手を削除", "/original-hand/save", "/original-hand/delete", "/room/start", "/api/status")) {
            assertFalse(html.contains(text), text);
        }
    }
    @Test void realHttpTwoPlayerFlowRulesReadyCancelStalePostsLeaveAndLogout() throws Exception {
        var a = browser(); var b = browser();
        redirect(http(a, "/login", "username=Host"), "/rooms");
        redirect(http(b, "/login", "username=Member"), "/rooms");
        redirect(http(a, "/rooms/enter", "roomName=R"), "/room");
        redirect(http(b, "/rooms/enter", "roomName=R"), "/room");
        String waiting = http(a, "/room", null).body(); String id = hiddenRoom(waiting);
        assertTrue(waiting.contains("準備完了")); assertFalse(waiting.contains("準備取消"));
        assertTrue(waiting.contains("action=\"/room/rules\"")); assertTrue(waiting.contains("value=\"3\""));
        assertTrue(waiting.contains("ルーム名をコピー"));
        assertFalse(http(b, "/room", null).body().contains("action=\"/room/rules\""));
        redirect(http(a, "/room/rules", "roomId=" + id + "&targetWins=5&preventConsecutiveSameOriginalHand=true"), "/room");
        createAndReady(a, id, "HA"); createAndReady(b, id, "HB");
        readyUi(http(a, "/room", null).body()); readyUi(http(b, "/room", null).body());
        for (String path : List.of("/", "/rooms")) { redirect(http(a, path, null), "/room"); }
        redirect(http(a, "/room/rules", "roomId=" + id + "&targetWins=7"), "/room");
        readyUi(http(a, "/room", null).body()); readyUi(http(b, "/room", null).body());
        synchronized (lock) {
            var r = rooms.findById(UUID.fromString(id)).orElseThrow();
            assertEquals(7, r.getTargetWins()); assertFalse(r.isPreventConsecutiveSameOriginalHand());
            assertTrue(users.findAll().stream().allMatch(u -> u.getState() == UserState.READY));
        }
        for (String path : List.of("/original-hand/save", "/original-hand/delete")) {
            var response = http(a, path, path.endsWith("save") ? saveBody(id, "changed") : "returnPage=ROOM&roomId=" + id);
            assertEquals(409, response.statusCode()); assertTrue(response.body().contains("INVALID_STATE"));
            readyUi(response.body()); assertTrue(response.body().contains("HA"));
        }
        redirect(http(a, "/room/ready/cancel", "roomId=" + id), "/room");
        assertTrue(http(a, "/room", null).body().contains("オリジナル手を編集"));
        redirect(http(a, "/room/ready", "roomId=" + id), "/room");
        redirect(http(a, "/room/leave", "roomId=" + id), "/rooms");
        assertTrue(http(a, "/rooms", null).body().contains("HA"));
        assertTrue(http(b, "/room", null).body().matches("(?s).*Member</span><strong[^>]*>（ホスト）.*"));
        redirect(http(b, "/logout", ""), "/"); redirect(http(b, "/room", null), "/");
        synchronized (lock) { assertTrue(rooms.findAll().isEmpty()); assertEquals(1, users.findAll().size()); }
        redirect(http(a, "/logout", ""), "/");
    }
    @Test void realHttpReadyHostLogoutTransfersHostAndLastReadyLeaves() throws Exception {
        var a = browser(); var b = browser();
        redirect(http(a, "/login", "username=A"), "/rooms"); redirect(http(b, "/login", "username=B"), "/rooms");
        redirect(http(a, "/rooms/enter", "roomName=R"), "/room"); redirect(http(b, "/rooms/enter", "roomName=R"), "/room");
        String id = hiddenRoom(http(a, "/room", null).body()); createAndReady(a, id, "HA"); createAndReady(b, id, "HB");
        redirect(http(a, "/logout", ""), "/");
        String html = http(b, "/room", null).body(); readyUi(html);
        assertTrue(html.matches("(?s).*B</span><strong[^>]*>（ホスト）.*")); assertTrue(html.contains("action=\"/room/rules\""));
        redirect(http(b, "/room/leave", "roomId=" + id), "/rooms");
        synchronized (lock) { assertTrue(rooms.findAll().isEmpty()); assertEquals(1, users.findAll().size()); }
        redirect(http(b, "/logout", ""), "/");
    }
    @Test void realHttpInvalidRulesKeepEnteredTextAndBothSavedRules() throws Exception {
        var a = browser(); redirect(http(a, "/login", "username=A"), "/rooms");
        redirect(http(a, "/rooms/enter", "roomName=R"), "/room"); String id = hiddenRoom(http(a, "/room", null).body());
        var response = http(a, "/room/rules", "roomId=" + id + "&targetWins=abc&preventConsecutiveSameOriginalHand=true");
        assertEquals(400, response.statusCode()); assertTrue(response.body().contains("VALIDATION_ERROR"));
        assertTrue(response.body().contains("value=\"abc\"")); assertTrue(response.body().contains("checked=\"checked\""));
        synchronized (lock) { var r = rooms.findById(UUID.fromString(id)).orElseThrow(); assertEquals(3, r.getTargetWins()); assertFalse(r.isPreventConsecutiveSameOriginalHand()); }
        redirect(http(a, "/logout", ""), "/");
    }
}
