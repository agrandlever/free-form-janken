package com.example.janken.controller;

import com.example.janken.service.*;
import com.example.janken.store.*;
import com.example.janken.domain.enums.UserState;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** TC-077～079、110、113、116、122～123、127～129の導入済み部分をHTTP経由で確認。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StageFourWebTests.FixedTime.class)
class StageFourWebTests {
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC); }
    }
    @LocalServerPort int port;
    @Autowired AuthController auth;
    @Autowired RoomController room;
    @Autowired HtmlErrorHandler errors;
    @Autowired GameStateLock lock;
    @Autowired UserStore users;
    @Autowired RoomStore rooms;
    MockMvc mvc;

    @BeforeEach void setup() {
        synchronized(lock) {
            rooms.findAll().forEach(r->rooms.deleteById(r.getId()));
            users.findAll().forEach(u->users.deleteById(u.getId()));
        }
        // Modelの契約はMockMvcで、実際のテンプレート描画は別の実HTTPテストで確認する。
        mvc=MockMvcBuilders.standaloneSetup(auth,room).setControllerAdvice(errors)
            .setViewResolvers((name,locale)->name.startsWith("redirect:")
                ? new RedirectView(name.substring(9)) : new View() {
                public void render(Map<String,?> model,jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response) { }
            }).build();
    }
    MockHttpSession login() throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").param("username"," A "))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/rooms")).andReturn().getRequest().getSession(false);
    }
    String enter(MockHttpSession session) throws Exception {
        mvc.perform(post("/rooms/enter").session(session).param("roomName"," R "))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/room"));
        return (String)mvc.perform(get("/room").session(session)).andExpect(status().isOk())
            .andReturn().getModelAndView().getModel().get("roomId");
    }
    void htmlError(ResultActions result,int status,String code,String view) throws Exception {
        result.andExpect(status().is(status)).andExpect(view().name(view))
            .andExpect(model().attribute("errorCode",code))
            .andExpect(model().attributeExists("errorMessages","fieldErrors","form"));
    }

    @Test void getAccessControlForAllImplementedScreens() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("login")).andExpect(model().attributeExists("loginForm"));
        for(String path:List.of("/rooms","/room")) mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        MockHttpSession session=login();
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/rooms"));
        mvc.perform(get("/room").session(session)).andExpect(redirectedUrl("/rooms"));
        mvc.perform(get("/rooms").session(session)).andExpect(status().isOk()).andExpect(model().attribute("username","A"))
            .andExpect(model().attribute("userState",UserState.ROOM_NONE)).andExpect(model().attributeExists("roomEnterForm"));
        enter(session);
        for(String path:List.of("/","/rooms")) mvc.perform(get(path).session(session)).andExpect(redirectedUrl("/room"));
        mvc.perform(get("/room").session(session)).andExpect(status().isOk())
            .andExpect(model().attributeExists("username","userState","roomName","members","isHost","roomActionForm"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"　\t ","123456789012345678901","😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"})
    void loginAndEnterValidationAndStatePriority(String input) throws Exception {
        MockHttpServletRequestBuilder login=post("/login"); if(input!=null) login.param("username",input);
        htmlError(mvc.perform(login),400,"VALIDATION_ERROR","login");
        MockHttpServletRequestBuilder anonymous=post("/rooms/enter"); if(input!=null) anonymous.param("roomName",input);
        htmlError(mvc.perform(anonymous),401,"LOGIN_REQUIRED","login");
        MockHttpSession session=login(); MockHttpServletRequestBuilder enter=post("/rooms/enter").session(session);
        if(input!=null) enter.param("roomName",input);
        MvcResult invalid=mvc.perform(enter).andReturn();
        assertEquals(400,invalid.getResponse().getStatus()); assertEquals("VALIDATION_ERROR",invalid.getModelAndView().getModel().get("errorCode"));
        assertEquals(input,((com.example.janken.form.RoomEnterForm)invalid.getModelAndView().getModel().get("form")).getRoomName());
        assertTrue(((Map<?,?>)invalid.getModelAndView().getModel().get("fieldErrors")).containsKey("roomName"));
        assertTrue(rooms.findAll().isEmpty()); enter(session);
        htmlError(mvc.perform(enter),409,"INVALID_STATE","room"); assertEquals(1,rooms.findAll().size());
        htmlError(mvc.perform(post("/login").session(session).param("username","new")),409,"INVALID_STATE","room");
    }

    @Test void fullRoomAndStaleLeaveErrorsRebuildNormalModel() throws Exception {
        for(int i=0;i<8;i++) enter(login());
        MockHttpSession extra=login();
        htmlError(mvc.perform(post("/rooms/enter").session(extra).param("roomName","R")),409,"ROOM_FULL","rooms");
        mvc.perform(post("/rooms/enter").session(extra).param("roomName","B")).andExpect(redirectedUrl("/room"));
        String current=(String)mvc.perform(get("/room").session(extra)).andReturn().getModelAndView().getModel().get("roomId");
        htmlError(mvc.perform(post("/room/leave").session(extra).param("roomId",UUID.randomUUID().toString())),409,"INVALID_STATE","room");
        htmlError(mvc.perform(post("/room/leave").session(extra).param("roomId","bad")),400,"VALIDATION_ERROR","room");
        mvc.perform(get("/room").session(extra)).andExpect(model().attribute("roomId",current));
    }

    @Test void leavePreservesSessionAndLogoutRejectsOldAccess() throws Exception {
        MockHttpSession session=login(); String id=enter(session);
        mvc.perform(post("/room/leave").session(session).param("roomId",id)).andExpect(redirectedUrl("/rooms"));
        mvc.perform(get("/rooms").session(session)).andExpect(status().isOk()).andExpect(model().attribute("username","A"));
        htmlError(mvc.perform(post("/room/leave").session(session).param("roomId",id)),409,"INVALID_STATE","rooms");
        mvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/")); assertTrue(session.isInvalid());
        for(String path:List.of("/rooms","/room")) mvc.perform(get(path)).andExpect(redirectedUrl("/"));
        for(String path:List.of("/logout","/rooms/enter","/room/leave")) htmlError(mvc.perform(post(path)),401,"LOGIN_REQUIRED","login");
    }

    HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    HttpResponse<String> http(HttpClient client,String path,String body) throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if(body==null) builder.GET(); else builder.header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    String hiddenRoom(String html) {
        var matcher=java.util.regex.Pattern.compile("name=\"roomId\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(),html); return matcher.group(1);
    }
    void redirect(HttpResponse<String> response,String path) {
        assertEquals(302,response.statusCode()); assertTrue(response.headers().firstValue("location").orElseThrow().endsWith(path), response.headers().toString());
    }

    @Test void actualBootHttpFlowRendersHtmlAndTransfersHostAndDeletesRoom() throws Exception {
        HttpClient a=browser(), b=browser();
        assertTrue(http(a,"/",null).body().contains("ログイン"));
        redirect(http(a,"/login","username=Host"),"/rooms");
        String roomName="共有<部屋>"; String encoded=URLEncoder.encode(roomName,StandardCharsets.UTF_8);
        redirect(http(a,"/rooms/enter","roomName="+encoded),"/room");
        String hostHtml=http(a,"/room",null).body(); String id=hiddenRoom(hostHtml);
        assertTrue(hostHtml.contains("共有&lt;部屋&gt;")); assertTrue(hostHtml.contains("（ホスト）"));
        assertEquals(2,hostHtml.split(id,-1).length-1); // 未作成時の内部IDは退出・保存のhiddenにだけ現れる。
        assertFalse(hostHtml.contains("/api/status")); assertFalse(hostHtml.contains("準備完了"));
        redirect(http(b,"/login","username=Member"),"/rooms");
        redirect(http(b,"/rooms/enter","roomName="+encoded),"/room"); assertEquals(id,hiddenRoom(http(b,"/room",null).body()));
        redirect(http(a,"/room/leave","roomId="+id),"/rooms");
        String remaining=http(b,"/room",null).body(); assertTrue(remaining.matches("(?s).*Member</span><strong[^>]*>（ホスト）.*"));
        redirect(http(b,"/room/leave","roomId="+id),"/rooms");
        synchronized(lock) { assertTrue(rooms.findById(UUID.fromString(id)).isEmpty()); assertTrue(rooms.findByName(roomName).isEmpty()); }
        redirect(http(a,"/logout",""),"/"); redirect(http(a,"/room",null),"/");
        assertEquals(401,http(a,"/rooms/enter","roomName="+encoded).statusCode());
        assertEquals(200,http(b,"/rooms",null).statusCode()); redirect(http(b,"/logout",""),"/");
    }

    @Test void actualBootLogoutHostAndLastMemberWithOldCookies() throws Exception {
        HttpClient a=browser(),b=browser(); redirect(http(a,"/login","username=A"),"/rooms");
        redirect(http(b,"/login","username=B"),"/rooms"); redirect(http(a,"/rooms/enter","roomName=logout"),"/room");
        redirect(http(b,"/rooms/enter","roomName=logout"),"/room"); redirect(http(a,"/logout",""),"/");
        assertTrue(http(b,"/room",null).body().matches("(?s).*B</span><strong[^>]*>（ホスト）.*"));
        for(String path:List.of("/rooms","/room")) redirect(http(a,path,null),"/");
        for(String path:List.of("/logout","/rooms/enter","/room/leave")) {
            HttpResponse<String> response=http(a,path,"roomName=logout&roomId="+UUID.randomUUID());
            assertEquals(401,response.statusCode()); assertTrue(response.body().contains("LOGIN_REQUIRED"));
        }
        redirect(http(b,"/logout",""),"/"); synchronized(lock) { assertTrue(rooms.findByName("logout").isEmpty()); }
    }
}
