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

/** ①・②の第5段階Model契約と実Spring BootのHTMLを確認する。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OriginalHandWebTests.FixedTime.class)
class OriginalHandWebTests {
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC); }
    }
    @LocalServerPort int port;
    @Autowired AuthController auth;
    @Autowired RoomController room;
    @Autowired HtmlErrorHandler errors;
    @Autowired OriginalHandController original;
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
        mvc=MockMvcBuilders.standaloneSetup(auth,room,original).setControllerAdvice(errors)
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


    MockHttpServletRequestBuilder save(MockHttpSession s, String id, String name) {
        var request=post("/original-hand/save").session(s).param("name",name).param("returnPage",id==null?"ROOMS":"ROOM")
            .param("vsRock","WIN").param("vsScissors","LOSE").param("vsPaper","DRAW").param("vsOriginal","DRAW");
        if(id!=null) request.param("roomId",id); return request;
    }
    MockHttpServletRequestBuilder delete(MockHttpSession s, String id) {
        var request=post("/original-hand/delete").session(s).param("returnPage",id==null?"ROOMS":"ROOM");
        if(id!=null) request.param("roomId",id); return request;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void successRedirectsFormsAndSavedName(boolean inRoom) throws Exception {
        MockHttpSession s=login(); String id=inRoom?enter(s):null; String path=inRoom?"/room":"/rooms";
        mvc.perform(get(path).session(s)).andExpect(model().attribute("hasOriginalHand",false))
            .andExpect(model().attribute("originalHandFormOpen",false));
        mvc.perform(save(s,id,"　初期名\t ")).andExpect(status().isFound()).andExpect(redirectedUrl(path));
        mvc.perform(get(path).session(s)).andExpect(model().attribute("hasOriginalHand",true)).andExpect(model().attribute("originalHandName","初期名"));
        UUID handId; synchronized(lock) { handId=users.findAll().getFirst().getOriginalHand().getId(); }
        mvc.perform(save(s,id,"更新名")).andExpect(redirectedUrl(path));
        synchronized(lock) { assertEquals(handId,users.findAll().getFirst().getOriginalHand().getId()); }
        mvc.perform(delete(s,id)).andExpect(status().isFound()).andExpect(redirectedUrl(path));
        mvc.perform(get(path).session(s)).andExpect(model().attribute("hasOriginalHand",false));
        htmlError(mvc.perform(delete(s,id)),409,"INVALID_STATE",inRoom?"room":"rooms");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> forbidden() {
        return java.util.stream.Stream.of(false,true).flatMap(inRoom -> java.util.stream.Stream.of(false,true).flatMap(update ->
            java.util.stream.Stream.of("グー","チョキ","パー"," グー ","\tチョキ　","　パー\t").map(name -> Arguments.of(inRoom,update,name))));
    }
    @ParameterizedTest @MethodSource("forbidden")
    void forbiddenNamesPreserveInputsSavedDisplayAndNormalModel(boolean inRoom,boolean update,String input) throws Exception {
        MockHttpSession s=login(); String id=inRoom?enter(s):null; if(update) mvc.perform(save(s,id,"保存済み")).andExpect(status().isFound());
        var result=mvc.perform(save(s,id,input)); htmlError(result,400,"VALIDATION_ERROR",inRoom?"room":"rooms");
        result.andExpect(model().attribute("originalHandFormOpen",true)).andExpect(model().attribute("originalHandName",update?"保存済み":""))
            .andExpect(model().attributeExists(inRoom?"members":"roomEnterForm"));
        var model=result.andReturn().getModelAndView().getModel();
        assertEquals(input,((com.example.janken.form.OriginalHandForm)model.get("originalHandForm")).getName());
        assertTrue(((List<?>)model.get("errorMessages")).contains(OriginalHandService.FORBIDDEN_NAME_MESSAGE));
        assertEquals(List.of(OriginalHandService.FORBIDDEN_NAME_MESSAGE),((Map<?,?>)model.get("fieldErrors")).get("name"));
    }
    static java.util.stream.Stream<Arguments> relationInputs() {
        return java.util.stream.Stream.of(false,true).flatMap(inRoom -> java.util.stream.Stream.of("vsRock","vsScissors","vsPaper","vsOriginal")
            .flatMap(field -> java.util.stream.Stream.of("","INVALID"," win ","WIN ").map(value -> Arguments.of(inRoom,field,value))));
    }
    @ParameterizedTest @MethodSource("relationInputs")
    void relationTypesAndMissingValuesReturn400WithInput(boolean inRoom,String field,String value) throws Exception {
        MockHttpSession s=login(); String id=inRoom?enter(s):null;
        MockHttpServletRequestBuilder request=save(s,id,"入力名"); request.with(r -> { r.setParameter(field,value); return r; });
        var result=mvc.perform(request); htmlError(result,400,"VALIDATION_ERROR",inRoom?"room":"rooms");
        result.andExpect(model().attribute("originalHandFormOpen",true));
        Map<String,Object> model=result.andReturn().getModelAndView().getModel();
        assertTrue(((Map<?,?>)model.get("fieldErrors")).containsKey(field));
        synchronized(lock) { assertNull(users.findAll().getFirst().getOriginalHand()); }
        if(!value.isEmpty()) assertEquals(value,((Map<?,?>)model.get("originalHandRejectedValues")).get(field));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void noLoseAndMultipleErrorsShownWithoutChanges(boolean inRoom) throws Exception {
        MockHttpSession s=login(); String id=inRoom?enter(s):null;
        var request=save(s,id,"入力名"); request.with(r -> { r.setParameter("vsScissors","WIN"); return r; });
        htmlError(mvc.perform(request),400,"VALIDATION_ERROR",inRoom?"room":"rooms");
        var multiple=post("/original-hand/save").session(s).param("returnPage",inRoom?"ROOM":"ROOMS").param("name","グー");
        if(id!=null) multiple.param("roomId",id);
        Map<String,Object> model=mvc.perform(multiple).andExpect(status().isBadRequest()).andReturn().getModelAndView().getModel();
        assertEquals(5,((Map<?,?>)model.get("fieldErrors")).size()); assertTrue(((List<?>)model.get("errorMessages")).size()>1);
    }
    @Test void anonymousSaveAndDeleteAndSubmittedUserIdCannotChooseUser() throws Exception {
        for(String path:List.of("/original-hand/save","/original-hand/delete")) htmlError(mvc.perform(post(path)),401,"LOGIN_REQUIRED","login");
        MockHttpSession a=login(),b=login(); UUID other=(UUID)b.getAttribute(SessionUserAccess.USER_ID);
        mvc.perform(save(a,null,"本人").param("userId",other.toString())).andExpect(status().isFound());
        synchronized(lock) { assertNull(users.findById(other).orElseThrow().getOriginalHand()); }
    }
    @Test void roomTargetsDistinguish400And409AndRebuildCurrentScreen() throws Exception {
        MockHttpSession s=login(); String id=enter(s); mvc.perform(save(s,id,"保存済み")).andExpect(status().isFound());
        for(String target:List.of("","bad","1-1-1-1-1")) {
            var saved=mvc.perform(save(s,target,"変更"));
            htmlError(saved,400,"VALIDATION_ERROR","room");
            var redisplayed=saved.andReturn().getModelAndView().getModel();
            assertEquals(target,((com.example.janken.form.OriginalHandForm)redisplayed.get("form")).getRoomId());
            assertEquals(id,((com.example.janken.form.OriginalHandForm)redisplayed.get("originalHandForm")).getRoomId());
            htmlError(mvc.perform(delete(s,target)),400,"VALIDATION_ERROR","room");
        }
        String other=UUID.randomUUID().toString();
        for(var request:List.of(save(s,other,"変更"),delete(s,other))) {
            mvc.perform(request).andExpect(status().isConflict()).andExpect(view().name("room"))
                .andExpect(model().attribute("roomId",id)).andExpect(model().attribute("originalHandFormOpen",false));
        }
        mvc.perform(post("/original-hand/save").session(s).param("returnPage","ROOM")).andExpect(status().isBadRequest());
        mvc.perform(post("/original-hand/delete").session(s).param("returnPage","ROOM")).andExpect(status().isBadRequest());
        mvc.perform(post("/room/leave").session(s).param("roomId",id)).andExpect(status().isFound());
        htmlError(mvc.perform(save(s,id,"変更")),409,"INVALID_STATE","rooms");
        enter(s); htmlError(mvc.perform(delete(s,id)),409,"INVALID_STATE","room");
        synchronized(lock) { assertEquals("保存済み",users.findAll().getFirst().getOriginalHand().getName()); }
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


    String body(String name,String page,String id) {
        return "name="+URLEncoder.encode(name,StandardCharsets.UTF_8)+"&returnPage="+page+(id==null?"":"&roomId="+id)
            +"&vsRock=WIN&vsScissors=LOSE&vsPaper=DRAW&vsOriginal=DRAW";
    }
    void panelOpen(String html) {
        var m=java.util.regex.Pattern.compile("<div id=\"original-hand-panel\"([^>]*)>").matcher(html);
        assertTrue(m.find(),html); assertFalse(m.group(1).contains("hidden"),html);
    }
    @Test void actualSpringBootCreateEditDeleteInBothScreensAndSameFormErrors() throws Exception {
        HttpClient a=browser(); redirect(http(a,"/login","username=User"),"/rooms");
        for(boolean inRoom:List.of(false,true)) {
            String path=inRoom?"/room":"/rooms", page=inRoom?"ROOM":"ROOMS"; String id=null;
            if(inRoom) {redirect(http(a,"/rooms/enter","roomName=Room"),"/room");id=hiddenRoom(http(a,path,null).body());}
            String initial=http(a,path,null).body(); assertTrue(initial.contains("オリジナル手が作成されていません"));
            assertTrue(initial.contains("オリジナル手を作成")); assertFalse(initial.contains("オリジナル手を編集"));
            redirect(http(a,"/original-hand/save",body("　初期<手>\t ",page,id)),path);
            String created=http(a,path,null).body(); assertTrue(created.contains("初期&lt;手&gt;")); assertTrue(created.contains("オリジナル手を編集"));
            assertFalse(created.contains("オリジナル手を作成")); assertTrue(created.contains("オリジナル手を削除"));
            redirect(http(a,"/original-hand/save",body(" 更新名 ",page,id)),path);
            String updated=http(a,path,null).body(); assertTrue(updated.contains("更新名"));
            var invalid=http(a,"/original-hand/save",body(" グー ",page,id)); assertEquals(400,invalid.statusCode()); panelOpen(invalid.body());
            assertTrue(invalid.body().contains("value=\" グー \"")); assertTrue(invalid.body().contains("更新名"));
            assertTrue(invalid.body().contains(OriginalHandService.FORBIDDEN_NAME_MESSAGE));
            var relation=http(a,"/original-hand/save",body("編集中",page,id).replace("vsRock=WIN","vsRock=BAD"));
            assertEquals(400,relation.statusCode()); panelOpen(relation.body()); assertTrue(relation.body().contains("value=\"BAD\" selected=\"selected\""),relation.body());
            assertTrue(http(a,path,null).body().contains("更新名"));
            if(inRoom) {
                assertTrue(updated.contains("ルーム名をコピー")); assertTrue(updated.contains("/js/room.js"));
                assertTrue(updated.contains("ルームを退出")); assertTrue(updated.contains("（ホスト）")); assertTrue(updated.contains("参加者一覧"));
            }
            redirect(http(a,"/original-hand/delete","returnPage="+page+(id==null?"":"&roomId="+id)),path);
            String deleted=http(a,path,null).body(); assertTrue(deleted.contains("オリジナル手が作成されていません")); assertTrue(deleted.contains("オリジナル手を作成"));
            assertFalse(deleted.contains("オリジナル手を編集")); assertFalse(deleted.contains("オリジナル手を削除"));
        }
        redirect(http(a,"/logout",""),"/");
    }
}
