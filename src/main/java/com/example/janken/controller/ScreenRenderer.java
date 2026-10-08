package com.example.janken.controller;

import com.example.janken.form.LoginForm;
import com.example.janken.form.OriginalHandForm;
import com.example.janken.form.OriginalHandDeleteForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.form.RoomActionForm;
import com.example.janken.form.RoomRuleForm;
import com.example.janken.service.GameOperationException;
import com.example.janken.service.ScreenService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class ScreenRenderer {
    private final ScreenService screens;
    private final com.example.janken.service.MatchResultService results;
    public ScreenRenderer(ScreenService screens, com.example.janken.service.MatchResultService results) {
        this.screens = screens; this.results = results;
    }

    public String get(HttpSession session, String requestedPath, Model model) {
        ScreenService.Screen screen = screens.current(session);
        if (!screen.path().equals(requestedPath)) { return "redirect:" + screen.path(); }
        model.addAllAttributes(screen.model());
        return screen.template();
    }

    public String play(HttpSession session, String matchId, Model model) {
        return matchPage(session, matchId, "play", model);
    }

    public String roundResult(HttpSession session, String matchId, Model model) {
        return matchPage(session, matchId, "round-result", model);
    }

    private String matchPage(HttpSession session, String matchId, String template, Model model) {
        ScreenService.Screen screen = screens.gamePage(session, matchId);
        if (!screen.template().equals(template)) {
            if ("/room".equals(screen.path()) && matchId != null) {
                // 保存済みSnapshotだけが残る場合も、同じIDの閲覧権限を確認する。
                return "redirect:" + results.page(session, matchId).path();
            }
            return "redirect:" + screen.path();
        }
        // 無指定なら本人の現在対戦を表示。異なるIDは本人の現在URLへ誘導する。
        if (matchId != null && !matchId.equals(screen.model().get("matchId"))) {
            return "redirect:" + screen.path();
        }
        model.addAllAttributes(screen.model());
        return screen.template();
    }

    public String error(HttpSession session, Object form, GameOperationException error, Model model) {
        ScreenService.Screen screen = screens.current(session);
        String resultId = form instanceof OriginalHandForm hand ? hand.getResultMatchId()
                : form instanceof OriginalHandDeleteForm delete ? delete.getResultMatchId() : null;
        String returnPage = form instanceof OriginalHandForm hand ? hand.getReturnPage()
                : form instanceof OriginalHandDeleteForm delete ? delete.getReturnPage() : null;
        if ("MATCH_RESULT".equals(returnPage) && resultId != null && !resultId.isEmpty()) {
            // 明示された対象だけを再表示し、最新結果への再解決はしない。
            ScreenService.Screen result = results.page(session, resultId);
            if (result.template().equals("match-result")) { screen = result; }
        }
        model.addAllAttributes(screen.model());
        model.addAttribute("errorCode", error.getCode());
        model.addAttribute("errorMessages", error.getErrorMessages());
        model.addAttribute("fieldErrors", error.getFieldErrors());
        model.addAttribute("form", form);
        if (screen.template().equals("login") && form instanceof LoginForm) {
            model.addAttribute("loginForm", form);
        } else if (screen.template().equals("rooms") && form instanceof RoomEnterForm) {
            model.addAttribute("roomEnterForm", form);
        } else if (screen.template().equals("room") && form instanceof RoomActionForm action
                && action.getRoomId() != null && action.getRoomId().equals(screen.model().get("roomId"))) {
            // 古い所属IDはhiddenへ再利用せず、現在の検証済みIDを使う。
            model.addAttribute("roomActionForm", form);
        }
        if (form instanceof RoomRuleForm rules && screen.template().equals("room")
                && Boolean.TRUE.equals(screen.model().get("isHost")) && error.getStatus() == 400) {
            RoomRuleForm display = new RoomRuleForm();
            display.setRoomId((String) screen.model().get("roomId"));
            display.setTargetWins(rules.getTargetWins());
            display.setPreventConsecutiveSameOriginalHand(rules.isPreventConsecutiveSameOriginalHand());
            model.addAttribute("roomRuleForm", display);
        }
        if (Boolean.TRUE.equals(screen.model().get("canEditOriginalHand"))
                && form instanceof OriginalHandForm hand && canRedisplay(screen, hand.getReturnPage(), hand.getRoomId())) {
            OriginalHandForm current = (OriginalHandForm) screen.model().get("originalHandForm");
            // 入力値は維持し、hiddenの対象は現在画面の検証済み値を使う。
            OriginalHandForm display = new OriginalHandForm();
            display.setName(hand.getName());
            display.setVsRock(hand.getVsRock());
            display.setVsScissors(hand.getVsScissors());
            display.setVsPaper(hand.getVsPaper());
            display.setVsOriginal(hand.getVsOriginal());
            display.setReturnPage(current.getReturnPage());
            display.setRoomId(current.getRoomId());
            display.setResultMatchId(current.getResultMatchId());
            model.addAttribute("originalHandForm", display);
            model.addAttribute("originalHandFormOpen", true);
        } else if (Boolean.TRUE.equals(screen.model().get("canEditOriginalHand"))
                && form instanceof OriginalHandDeleteForm delete
                && canRedisplay(screen, delete.getReturnPage(), delete.getRoomId())) {
            model.addAttribute("originalHandFormOpen", true);
        }
        return screen.template();
    }

    private boolean canRedisplay(ScreenService.Screen screen, String returnPage, String roomId) {
        if ("MATCH_RESULT".equals(returnPage) && screen.template().equals("match-result")) {
            // 形式不正のhiddenは現在の検証済み値で置き換える。別RoomのUUIDは使わない。
            try {
                java.util.UUID target = java.util.UUID.fromString(roomId == null ? "" : roomId);
                return !target.toString().equalsIgnoreCase(roomId)
                        || target.toString().equals(screen.model().get("roomId"));
            } catch (IllegalArgumentException ex) { return true; }
        }
        if ("ROOMS".equals(returnPage)) { return screen.template().equals("rooms"); }
        if (!"ROOM".equals(returnPage) || !screen.template().equals("room")) { return false; }
        try {
            java.util.UUID target = java.util.UUID.fromString(roomId == null ? "" : roomId);
            if (!target.toString().equalsIgnoreCase(roomId)) { return true; }
            return target.toString().equals(screen.model().get("roomId"));
        } catch (IllegalArgumentException ex) {
            // 対象の形式エラーは現在ルームで再表示する。別ルームUUIDは再利用しない。
            return true;
        }
    }
}
