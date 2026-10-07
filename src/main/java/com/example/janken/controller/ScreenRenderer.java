package com.example.janken.controller;

import com.example.janken.form.LoginForm;
import com.example.janken.form.RoomEnterForm;
import com.example.janken.form.RoomActionForm;
import com.example.janken.service.GameOperationException;
import com.example.janken.service.ScreenService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class ScreenRenderer {
    private final ScreenService screens;
    public ScreenRenderer(ScreenService screens) { this.screens = screens; }

    public String get(HttpSession session, String requestedPath, Model model) {
        ScreenService.Screen screen = screens.current(session);
        if (!screen.path().equals(requestedPath)) { return "redirect:" + screen.path(); }
        model.addAllAttributes(screen.model());
        return screen.template();
    }

    public String error(HttpSession session, Object form, GameOperationException error, Model model) {
        ScreenService.Screen screen = screens.current(session);
        model.addAllAttributes(screen.model());
        model.addAttribute("errorCode", error.getCode());
        model.addAttribute("errorMessages", List.of(error.getMessage()));
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
        return screen.template();
    }
}
