package com.example.janken.controller;

import com.example.janken.domain.*;
import com.example.janken.domain.enums.*;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageTenWebTests {
    @LocalServerPort int port;
    @Autowired MatchStore matches;
    @Autowired GameStateLock lock;
    @Autowired UserStore users;
    @Autowired RoomStore rooms;
    @BeforeEach void setup() {
        synchronized (lock) {
            matches.findAll().forEach(m -> matches.deleteById(m.getId()));
            rooms.findAll().forEach(r -> rooms.deleteById(r.getId()));
            users.findAll().forEach(u -> users.deleteById(u.getId()));
        }
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


    String[] startHttp(HttpClient a,HttpClient b,boolean prevent) throws Exception {
        redirect(http(a,"/login","username=Alice"),"/rooms"); redirect(http(b,"/login","username=Bob"),"/rooms");
        redirect(http(a,"/rooms/enter","roomName=R"),"/room"); redirect(http(b,"/rooms/enter","roomName=R"),"/room");
        String roomId=hiddenRoom(http(a,"/room",null).body()); createAndReady(a,roomId,"HA"); createAndReady(b,roomId,"HB");
        redirect(http(a,"/room/rules","roomId="+roomId+"&targetWins=3&preventConsecutiveSameOriginalHand="+prevent),"/room");
        var started=http(a,"/room/start","roomId="+roomId); String matchId;
        synchronized(lock){ matchId=rooms.findById(UUID.fromString(roomId)).orElseThrow().getCurrentMatchId().toString(); }
        redirect(started,"/play?matchId="+matchId); return new String[]{roomId,matchId};
    }
    String normalBody(String matchId,String hand) {return "matchId="+matchId+"&roundNumber=1&type=NORMAL&normalHand="+hand;}

    GameMatch match(String id) { return matches.findById(UUID.fromString(id)).orElseThrow(); }
    UUID alice(GameMatch match) {
        return match.getParticipants().values().stream().filter(p -> p.getUsername().equals("Alice")).findFirst().orElseThrow().getUserId();
    }
    String originalBody(String mid, int round, UUID handId) {
        return "matchId=" + mid + "&roundNumber=" + round + "&type=ORIGINAL&originalHandId=" + handId;
    }
    String normalBody(String mid, int round, NormalHandType hand) {
        return "matchId=" + mid + "&roundNumber=" + round + "&type=NORMAL&normalHand=" + hand;
    }
    int count(String text, String part) { return (text.length() - text.replace(part, "").length()) / part.length(); }
    List<String> handForms(String html) {
        var matcher = java.util.regex.Pattern.compile("<form[^>]*action=\"/play\"[^>]*>.*?</form>", java.util.regex.Pattern.DOTALL).matcher(html);
        var forms = new ArrayList<String>(); while (matcher.find()) { forms.add(matcher.group()); }
        assertEquals(5, forms.size()); return forms;
    }
    String originalForm(String html, UUID id) {
        return handForms(html).stream().filter(f -> f.contains(id.toString())).findFirst().orElseThrow();
    }
    void assertPrivate(String html) {
        for (String key : List.of("vsRock", "vsScissors", "vsPaper", "vsOriginal", "/api/status", "/round-result")) {
            assertFalse(html.contains(key), key);
        }
    }
    @ParameterizedTest
    @CsvSource({"false,original,false", "true,none,false", "true,normal,false", "true,original,false",
                "true,original,true", "false,original,true"})
    void getPlayDisablesOnlyRestrictedOriginalUnlessAlreadyConfirmed(boolean prevent, String previous, boolean confirmed) throws Exception {
        var a = browser(); var b = browser(); String mid = startHttp(a, b, prevent)[1]; UUID idA, idB;
        synchronized (lock) {
            var m = match(mid); idA = m.getOriginalHands().get(0).getHandId(); idB = m.getOriginalHands().get(1).getHandId();
            var self = m.getParticipants().get(alice(m));
            self.setPreviousHand(previous.equals("none") ? null : previous.equals("normal")
                    ? new HandSelection(SelectedHandType.NORMAL, NormalHandType.ROCK, null)
                    : new HandSelection(SelectedHandType.ORIGINAL, null, idA));
            if (confirmed) { m.getCurrentRound().getSelections().put(self.getUserId(), new HandSelection(SelectedHandType.NORMAL, NormalHandType.PAPER, null)); }
        }
        var response = http(a, "/play?matchId=" + mid, null); assertEquals(200, response.statusCode()); String html = response.body();
        boolean restricted = prevent && previous.equals("original");
        assertEquals(confirmed || restricted, originalForm(html, idA).contains("disabled=\"disabled\""));
        assertEquals(confirmed, originalForm(html, idB).contains("disabled=\"disabled\""));
        var normals = handForms(html).stream().filter(f -> f.contains("value=\"NORMAL\"")).toList(); assertEquals(3, normals.size());
        for (var form : normals) { assertEquals(confirmed, form.contains("disabled=\"disabled\"")); }
        assertEquals(confirmed ? 5 : restricted ? 1 : 0, count(html, "disabled=\"disabled\""));
        assertEquals(restricted, html.contains("前のラウンドと同じオリジナル手は選択できません。"));
        assertPrivate(html);
        if (confirmed) {
            var retry = http(a, "/play", originalBody(mid, 1, idA));
            assertEquals(409, retry.statusCode()); assertTrue(retry.body().contains("INVALID_STATE"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ROCK", "SCISSORS", "PAPER", "otherOriginal"})
    void realHttpRejectsDirectRestrictedPostThenAcceptsAlternative(String alternative) throws Exception {
        var a = browser(); var b = browser(); String mid = startHttp(a, b, true)[1]; UUID idA, idB, user;
        synchronized (lock) {
            var m = match(mid); idA = m.getOriginalHands().get(0).getHandId(); idB = m.getOriginalHands().get(1).getHandId(); user = alice(m);
            m.getParticipants().get(user).setPreviousHand(new HandSelection(SelectedHandType.ORIGINAL, null, idA));
            // 次ラウンドの準備はテスト内だけで行い、実装へAPIやボタンを追加しない。
            m.setCurrentRound(new Round(2, java.time.Instant.now()));
        }
        var rejected = http(a, "/play", originalBody(mid, 2, idA));
        assertEquals(400, rejected.statusCode()); assertTrue(rejected.body().contains("VALIDATION_ERROR"));
        assertTrue(rejected.body().contains("前のラウンドと同じオリジナル手は選択できません。")); assertPrivate(rejected.body());
        synchronized (lock) {
            var m = match(mid); assertTrue(m.getCurrentRound().getSelections().isEmpty());
            assertEquals(idA, m.getParticipants().get(user).getPreviousHand().getOriginalHandId());
            assertEquals(0, m.getParticipants().get(user).getScore()); assertTrue(m.getRoundHistory().isEmpty());
            assertEquals(MatchState.SELECTING_HAND, m.getState()); assertNull(m.getPendingEndType());
            assertTrue(m.getPendingWinnerIds().isEmpty()); assertNull(m.getTransitionAt());
        }
        String body = alternative.equals("otherOriginal") ? originalBody(mid, 2, idB) : normalBody(mid, 2, NormalHandType.valueOf(alternative));
        redirect(http(a, "/play", body), "/play?matchId=" + mid);
        synchronized (lock) { assertTrue(match(mid).getCurrentRound().getSelections().containsKey(user)); }
    }
    @Test void realHttpOffAllowsOriginalAfterNoWinnerRound() throws Exception {
        var a = browser(); var b = browser(); String mid = startHttp(a, b, false)[1]; UUID id;
        synchronized (lock) { id = match(mid).getOriginalHands().getFirst().getHandId(); }
        redirect(http(a, "/play", originalBody(mid, 1, id)), "/play?matchId=" + mid);
        redirect(http(b, "/play", originalBody(mid, 1, id)), "/play?matchId=" + mid);
        synchronized (lock) {
            var m = match(mid); assertFalse(m.getRoundHistory().getFirst().isHasWinner());
            assertEquals(id, m.getParticipants().get(alice(m)).getPreviousHand().getOriginalHandId());
            m.setCurrentRound(new Round(2, java.time.Instant.now())); m.setState(MatchState.SELECTING_HAND); m.setTransitionAt(null);
        }
        redirect(http(a, "/play", originalBody(mid, 2, id)), "/play?matchId=" + mid);
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "oldRound", "unknownId"})
    void realHttpErrorPriority(String kind) throws Exception {
        var a = browser(); var b = browser(); String mid = startHttp(a, b, true)[1]; UUID id;
        synchronized (lock) {
            var m = match(mid); id = kind.equals("unknownId") ? UUID.randomUUID() : m.getOriginalHands().getFirst().getHandId();
            m.getParticipants().get(alice(m)).setPreviousHand(new HandSelection(SelectedHandType.ORIGINAL, null, id));
            if (kind.equals("oldRound")) { m.setCurrentRound(new Round(2, java.time.Instant.now())); }
        }
        if (kind.equals("duplicate")) { redirect(http(a, "/play", normalBody(mid, "ROCK")), "/play?matchId=" + mid); }
        var response = http(a, "/play", originalBody(mid, 1, id));
        assertEquals(kind.equals("unknownId") ? 400 : 409, response.statusCode());
        assertTrue(response.body().contains(kind.equals("unknownId") ? "VALIDATION_ERROR" : "INVALID_STATE"));
        if (kind.equals("unknownId")) {
            assertTrue(response.body().contains("対戦開始時に固定された手を選択してください。"));
            synchronized (lock) { assertTrue(match(mid).getCurrentRound().getSelections().isEmpty()); }
        }
    }
}
